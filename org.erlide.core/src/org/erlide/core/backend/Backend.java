/*******************************************************************************
 * Copyright (c) 2009 Vlad Dumitrescu and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available
 * at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Vlad Dumitrescu
 *******************************************************************************/
package org.erlide.core.backend;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IContributor;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.RegistryFactory;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.IStreamListener;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.erlide.core.ErlangCore;
import org.erlide.core.ErlangPlugin;
import org.erlide.core.backend.console.BackendShell;
import org.erlide.core.backend.console.IoRequest.IoRequestKind;
import org.erlide.core.backend.events.EventDaemon;
import org.erlide.core.backend.events.LogEventHandler;
import org.erlide.core.backend.manager.BackendManager;
import org.erlide.core.backend.rpc.RpcException;
import org.erlide.core.backend.rpc.RpcFuture;
import org.erlide.core.backend.rpc.RpcHelper;
import org.erlide.core.backend.rpc.RpcResult;
import org.erlide.core.backend.runtimeinfo.RuntimeInfo;
import org.erlide.core.common.BeamUtil;
import org.erlide.core.common.IDisposable;
import org.erlide.core.internal.backend.CodeManager;
import org.erlide.core.internal.backend.ErlRuntime;
import org.erlide.core.internal.backend.InitialCall;
import org.erlide.core.internal.backend.RpcResultImpl;
import org.erlide.core.model.debug.ErlangDebugHelper;
import org.erlide.core.model.debug.ErlangDebugNode;
import org.erlide.core.model.debug.ErlangDebugTarget;
import org.erlide.core.model.debug.ErlideDebug;
import org.erlide.core.model.erlang.ErlModelException;
import org.erlide.core.model.erlang.IErlProject;
import org.erlide.core.model.erlang.util.CoreUtil;
import org.erlide.core.model.erlang.util.ErlideUtil;
import org.erlide.jinterface.ErlLogger;
import org.osgi.framework.Bundle;

import com.ericsson.otp.erlang.OtpErlang;
import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangDecodeException;
import com.ericsson.otp.erlang.OtpErlangExit;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangString;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpMbox;
import com.ericsson.otp.erlang.OtpNode;
import com.ericsson.otp.erlang.OtpNodeStatus;
import com.ericsson.otp.erlang.SignatureException;
import com.google.common.collect.Lists;

public class Backend implements RpcCallSite, IDisposable, IStreamListener {

    private static final String COULD_NOT_CONNECT_TO_BACKEND = "Could not connect to backend! Please check runtime settings.";
    private static final int EPMD_PORT = 4369;
    public static int DEFAULT_TIMEOUT;
    {
        setDefaultTimeout();
    }

    private final RuntimeInfo info;
    private final ErlRuntime runtime;
    private String currentVersion;
    private OtpMbox eventBox;
    private boolean stopped = false;
    private EventDaemon eventDaemon;
    private BackendShellManager shellManager;
    private final CodeManager codeManager;
    private ILaunch launch;
    private final boolean managed = false;
    private final BackendData data;
    private boolean disposable;

    public Backend(final BackendData data) throws BackendException {
        info = data.getRuntimeInfo();
        if (info == null) {
            throw new BackendException(
                    "Can't create backend without runtime information");
        }
        runtime = new ErlRuntime(info.getNodeName(), info.getCookie());
        this.data = data;
        codeManager = new CodeManager(this);
        disposable = false;

        launch = data.getLaunch();
        if (launch != null) {
            disposable = true;
            setLaunch(launch);
        }
    }

    public RpcResult call_noexception(final String m, final String f,
            final String signature, final Object... a) {
        return call_noexception(DEFAULT_TIMEOUT, m, f, signature, a);
    }

    public RpcResult call_noexception(final int timeout, final String m,
            final String f, final String signature, final Object... args) {
        try {
            final OtpErlangObject result = runtime.makeCall(timeout, m, f,
                    signature, args);
            return new RpcResultImpl(result);
        } catch (final RpcException e) {
            return RpcResultImpl.error(e.getMessage());
        } catch (final SignatureException e) {
            return RpcResultImpl.error(e.getMessage());
        }
    }

    public RpcFuture async_call(final String m, final String f,
            final String signature, final Object... args)
            throws BackendException {
        try {
            return runtime.makeAsyncCall(m, f, signature, args);
        } catch (final RpcException e) {
            throw new BackendException(e);
        } catch (final SignatureException e) {
            throw new BackendException(e);
        }
    }

    public void async_call_cb(final RpcCallback cb, final String m,
            final String f, final String signature, final Object... args)
            throws BackendException {
        try {
            runtime.makeAsyncCbCall(cb, m, f, signature, args);
        } catch (final RpcException e) {
            throw new BackendException(e);
        } catch (final SignatureException e) {
            throw new BackendException(e);
        }
    }

    public void cast(final String m, final String f, final String signature,
            final Object... args) throws BackendException {
        try {
            runtime.makeCast(m, f, signature, args);
        } catch (final RpcException e) {
            throw new BackendException(e);
        } catch (final SignatureException e) {
            throw new BackendException(e);
        }
    }

    public OtpErlangObject call(final String m, final String f,
            final String signature, final Object... a) throws BackendException {
        return call(DEFAULT_TIMEOUT, m, f, signature, a);
    }

    public OtpErlangObject call(final int timeout, final String m,
            final String f, final String signature, final Object... a)
            throws BackendException {
        return call(timeout, new OtpErlangAtom("user"), m, f, signature, a);
    }

    public OtpErlangObject call(final int timeout,
            final OtpErlangObject gleader, final String m, final String f,
            final String signature, final Object... a) throws BackendException {
        try {
            return runtime.makeCall(timeout, gleader, m, f, signature, a);
        } catch (final RpcException e) {
            throw new BackendException(e);
        } catch (final SignatureException e) {
            throw new BackendException(e);
        }
    }

    public void send(final OtpErlangPid pid, final Object msg) {
        if (!runtime.isAvailable()) {
            return;
        }
        try {
            RpcHelper.send(getNode(), pid, msg);
        } catch (final SignatureException e) {
            // shouldn't happen
            ErlLogger.warn(e);
        }
    }

    public void send(final String name, final Object msg) {
        if (!runtime.isAvailable()) {
            return;
        }
        try {
            RpcHelper.send(getNode(), getFullNodeName(), name, msg);
        } catch (final SignatureException e) {
            // shouldn't happen
            ErlLogger.warn(e);
        }
    }

    public OtpErlangObject receiveEvent(final long timeout)
            throws OtpErlangExit, OtpErlangDecodeException {
        if (eventBox == null) {
            return null;
        }
        return eventBox.receive(timeout);
    }

    public void connect() {
        final String label = getName();
        ErlLogger.debug(label + ": waiting connection to peer...");
        try {
            eventBox = getNode().createMbox("rex");
            wait_for_epmd();

            if (waitForCodeServer()) {
                ErlLogger.debug("connected!");
            } else {
                ErlLogger.error(COULD_NOT_CONNECT_TO_BACKEND);
            }

        } catch (final BackendException e) {
            ErlLogger.error(e);
            ErlLogger.error(COULD_NOT_CONNECT_TO_BACKEND);
        }
    }

    public void dispose() {
        // TODO review!
        if (disposable) {

            try {
                if (launch != null) {
                    launch.terminate();
                }
            } catch (final DebugException e) {
                e.printStackTrace();
            }
        }
        // FIXME need to stop process too, via ErtsProcess

        ErlLogger.debug("disposing backend " + getName());
        if (shellManager != null) {
            shellManager.dispose();
        }

        if (getNode() != null) {
            getNode().close();
        }
        if (eventDaemon != null) {
            eventDaemon.stop();
        }
    }

    public String getCurrentVersion() {
        if (currentVersion == null) {
            try {
                currentVersion = getScriptId();
            } catch (final Exception e) {
            }
        }
        return currentVersion;
    }

    private OtpMbox getEventBox() {
        return eventBox;
    }

    public OtpErlangPid getEventPid() {
        final OtpMbox theEventBox = getEventBox();
        if (theEventBox == null) {
            return null;
        }
        return theEventBox.self();
    }

    public RuntimeInfo getRuntimeInfo() {
        return info;
    }

    public String getName() {
        if (info == null) {
            return "<not_connected>";
        }
        return info.getNodeName();
    }

    public String getFullNodeName() {
        synchronized (runtime) {
            return runtime.getNodeName();
        }
    }

    private synchronized OtpNode getNode() {
        return runtime.getNode();
    }

    private String getScriptId() throws BackendException {
        OtpErlangObject r;
        r = call("init", "script_id", "");
        if (r instanceof OtpErlangTuple) {
            final OtpErlangObject rr = ((OtpErlangTuple) r).elementAt(1);
            if (rr instanceof OtpErlangString) {
                return ((OtpErlangString) rr).stringValue();
            }
        }
        return "";
    }

    private boolean init(final OtpErlangPid jRex, final boolean monitor,
            final boolean watch) {
        try {
            call("erlide_kernel_common", "init", "poo", jRex, monitor, watch);
            // TODO should use extension point!
            call("erlide_kernel_builder", "init", "");
            call("erlide_kernel_ide", "init", "");
            return true;
        } catch (final Exception e) {
            ErlLogger.error(e);
            return false;
        }
    }

    public boolean isStopped() {
        return stopped;
    }

    public synchronized void registerStatusHandler(final OtpNodeStatus handler) {
        getNode().registerStatusHandler(handler);
    }

    private void setRemoteRex(final OtpErlangPid watchdog) {
        try {
            getEventBox().link(watchdog);
        } catch (final OtpErlangExit e) {
        }
    }

    public void stop() {
        stopped = true;
    }

    private void wait_for_epmd() throws BackendException {
        wait_for_epmd("localhost");
    }

    private void wait_for_epmd(final String host) throws BackendException {
        // If anyone has a better solution for waiting for epmd to be up, please
        // let me know
        int tries = 50;
        boolean ok = false;
        do {
            Socket s;
            try {
                s = new Socket(host, EPMD_PORT);
                s.close();
                ok = true;
            } catch (final IOException e) {
            }
            try {
                Thread.sleep(100);
                // ErlLogger.debug("sleep............");
            } catch (final InterruptedException e1) {
            }
            tries--;
        } while (!ok && tries > 0);
        if (!ok) {
            final String msg = "Couldn't contact epmd - erlang backend is probably not working\n"
                    + "  Possibly your host's entry in /etc/hosts is wrong.";
            ErlLogger.error(msg);
            throw new BackendException(msg);
        }
    }

    private boolean waitForCodeServer() {
        try {
            OtpErlangObject r;
            int i = 10;
            do {
                r = call("erlang", "whereis", "a", "code_server");
                try {
                    Thread.sleep(200);
                } catch (final InterruptedException e) {
                }
                i--;
            } while (!(r instanceof OtpErlangPid) && i > 0);
            if (!(r instanceof OtpErlangPid)) {
                ErlLogger.error("code server did not start in time for %s",
                        getRuntimeInfo().getName());
                return false;
            }
            ErlLogger.debug("code server started");
            return true;
        } catch (final Exception e) {
            ErlLogger.error("error starting code server for %s: %s",
                    getRuntimeInfo().getName(), e.getMessage());
            return false;
        }
    }

    public EventDaemon getEventDaemon() {
        return eventDaemon;
    }

    public OtpMbox createMbox() {
        return getNode().createMbox();
    }

    public OtpMbox createMbox(final String name) {
        return getNode().createMbox(name);
    }

    private class BackendShellManager implements IDisposable {

        private final HashMap<String, BackendShell> fShells;

        public BackendShellManager() {
            fShells = new HashMap<String, BackendShell>();
        }

        public BackendShell getShell(final String id) {
            final BackendShell shell = fShells.get(id);
            return shell;
        }

        public synchronized BackendShell openShell(final String id) {
            BackendShell shell = getShell(id);
            if (shell == null) {
                shell = new BackendShell(Backend.this, id,
                        Backend.this.getEventPid());
                fShells.put(id, shell);
            }
            return shell;
        }

        public synchronized void closeShell(final String id) {
            final BackendShell shell = getShell(id);
            if (shell != null) {
                fShells.remove(id);
                shell.close();
            }
        }

        public void dispose() {
            final Collection<BackendShell> c = fShells.values();
            for (final BackendShell backendShell : c) {
                backendShell.close();
            }
            fShells.clear();
        }
    }

    private static void setDefaultTimeout() {
        final String t = System.getProperty("erlide.rpc.timeout", "9000");
        if ("infinity".equals(t)) {
            DEFAULT_TIMEOUT = RpcHelper.INFINITY;
        } else {
            try {
                DEFAULT_TIMEOUT = Integer.parseInt(t);
            } catch (final Exception e) {
                DEFAULT_TIMEOUT = 9000;
            }
        }
    }

    public void removePath(final String path) {
        codeManager.removePath(path);
    }

    public void addPath(final boolean usePathZ, final String path) {
        codeManager.addPath(usePathZ, path);
    }

    public synchronized void initErlang(final boolean monitor,
            final boolean watch) {
        final boolean inited = init(getEventPid(), monitor, watch);

        // data.monitor = monitor;
        // data.managed = watch;

        eventDaemon = new EventDaemon(this);
        eventDaemon.start();
        eventDaemon.addHandler(new LogEventHandler());

        ErlangCore.getBackendManager().addBackendListener(getEventDaemon());
    }

    public void register(final CodeBundle bundle) {
        codeManager.register(bundle);
    }

    public void unregister(final Bundle b) {
        codeManager.unregister(b);
    }

    public void setTrapExit(final boolean contains) {
    }

    public void streamAppended(final String text, final IStreamMonitor monitor) {
        final IStreamsProxy proxy = getStreamsProxy();
        if (monitor == proxy.getOutputStreamMonitor()) {
            // System.out.println(getName() + " OUT " + text);
        } else if (monitor == proxy.getErrorStreamMonitor()) {
            // System.out.println(getName() + " ERR " + text);
        } else {
            // System.out.println("???" + text);
        }
    }

    public ILaunch getLaunch() {
        return launch;
    }

    public void setLaunch(final ILaunch launch) {
        this.launch = launch;
        final IStreamsProxy proxy = getStreamsProxy();
        if (proxy != null) {
            final IStreamMonitor errorStreamMonitor = proxy
                    .getErrorStreamMonitor();
            errorStreamMonitor.addListener(this);
            final IStreamMonitor outputStreamMonitor = proxy
                    .getOutputStreamMonitor();
            outputStreamMonitor.addListener(this);
        }
    }

    public BackendShell getShell(final String id) {
        final BackendShell shell = shellManager.openShell(id);
        final IStreamsProxy proxy = getStreamsProxy();
        if (proxy != null) {
            final IStreamMonitor errorStreamMonitor = proxy
                    .getErrorStreamMonitor();
            errorStreamMonitor.addListener(new IStreamListener() {
                public void streamAppended(final String text,
                        final IStreamMonitor monitor) {
                    shell.add(text, IoRequestKind.STDERR);
                }
            });
            final IStreamMonitor outputStreamMonitor = proxy
                    .getOutputStreamMonitor();
            outputStreamMonitor.addListener(new IStreamListener() {
                public void streamAppended(final String text,
                        final IStreamMonitor monitor) {
                    shell.add(text, IoRequestKind.STDOUT);
                }
            });
        }
        return shell;
    }

    public boolean isDistributed() {
        return !getRuntimeInfo().getNodeName().equals("");
    }

    public void input(final String s) throws IOException {
        if (!isStopped()) {
            final IStreamsProxy proxy = getStreamsProxy();
            if (proxy != null) {
                proxy.write(s);
            } else {
                ErlLogger
                        .warn("Could not load module on backend %s, stream proxy is null",
                                getRuntimeInfo());
            }
        }
    }

    public void addProjectPath(final IProject project) {
        final IErlProject eproject = ErlangCore.getModel().findProject(project);
        final String outDir = project.getLocation()
                .append(eproject.getOutputLocation()).toOSString();
        if (outDir.length() > 0) {
            ErlLogger.debug("backend %s: add path %s", getName(), outDir);
            if (isDistributed()) {
                final boolean accessible = ErlideUtil
                        .isAccessible(this, outDir);
                if (accessible) {
                    addPath(false/* prefs.getUsePathZ() */, outDir);
                } else {
                    loadBeamsFromDir(outDir);
                }
            } else {
                final File f = new File(outDir);
                for (final File file : f.listFiles()) {
                    String name = file.getName();
                    if (!name.endsWith(".beam")) {
                        continue;
                    }
                    name = name.substring(0, name.length() - 5);
                    try {
                        CoreUtil.loadModuleViaInput(this, project, name);
                    } catch (final ErlModelException e) {
                        e.printStackTrace();
                    } catch (final IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public void removeProjectPath(final IProject project) {
        final IErlProject eproject = ErlangCore.getModel().findProject(project);
        final String outDir = project.getLocation()
                .append(eproject.getOutputLocation()).toOSString();
        if (outDir.length() > 0) {
            ErlLogger.debug("backend %s: add path %s", getName(), outDir);
            if (isDistributed()) {
                final boolean accessible = ErlideUtil
                        .isAccessible(this, outDir);
                if (accessible) {
                    removePath(outDir);
                } else {
                    // FIXME unloadBeamsFromDir(outDir);
                }
            } else {
                final File f = new File(outDir);
                for (final File file : f.listFiles()) {
                    String name = file.getName();
                    if (!name.endsWith(".beam")) {
                        continue;
                    }
                    name = name.substring(0, name.length() - 5);
                    // try {
                    // // FIXME CoreUtil.unloadModuleViaInput(this, project,
                    // // name);
                    // } catch (final ErlModelException e) {
                    // e.printStackTrace();
                    // } catch (final IOException e) {
                    // e.printStackTrace();
                    // }
                }
            }
        }
    }

    private void loadBeamsFromDir(final String outDir) {
        final File dir = new File(outDir);
        if (dir.isDirectory()) {
            for (final File f : dir.listFiles()) {
                final Path path = new Path(f.getPath());
                if (path.getFileExtension() != null
                        && "beam".compareTo(path.getFileExtension()) == 0) {
                    final String m = path.removeFileExtension().lastSegment();
                    try {
                        boolean ok = false;
                        final OtpErlangBinary bin = BeamUtil.getBeamBinary(m,
                                path);
                        if (bin != null) {
                            ok = ErlBackend.loadBeam(this, m, bin);
                        }
                        if (!ok) {
                            ErlLogger.error("Could not load %s", m);
                        }
                    } catch (final Exception ex) {
                        ErlLogger.warn(ex);
                    }
                }
            }
        }
    }

    public boolean isManaged() {
        return managed;
    }

    public boolean doLoadOnAllNodes() {
        return getRuntimeInfo().loadOnAllNodes();
    }

    public IStreamsProxy getStreamsProxy() {
        final ErtsProcess p = getErtsProcess();
        if (p == null) {
            return null;
        }
        return p.getStreamsProxy();
    }

    private void postLaunch() throws DebugException {
        final Collection<IProject> projects = Lists.newArrayList(data
                .getProjects());
        registerProjectsWithExecutionBackend(projects);
        if (!isDistributed()) {
            return;
        }
        if (data.isDebug()) {
            // add debug target
            final ErlangDebugTarget target = new ErlangDebugTarget(launch,
                    this, projects, data.getDebugFlags());
            // target.getWaiter().doWait();
            launch.addDebugTarget(target);
            // interpret everything we can
            final boolean distributed = (data.getDebugFlags() & ErlDebugConstants.DISTRIBUTED_DEBUG) != 0;
            if (distributed) {
                distributeDebuggerCode();
                addNodesAsDebugTargets(launch, target);
            }
            interpretModules(data, distributed);
            registerStartupFunctionStarter(data);
            target.sendStarted();
        } else {
            final InitialCall init_call = data.getInitialCall();
            if (init_call != null) {
                runInitial(init_call.getModule(), init_call.getName(),
                        init_call.getParameters());
            }
        }
    }

    private ErtsProcess getErtsProcess() {
        final IProcess[] ps = launch.getProcesses();
        if (ps == null || ps.length == 0) {
            return null;
        }
        return (ErtsProcess) ps[0];
    }

    private void registerProjectsWithExecutionBackend(
            final Collection<IProject> projects) {
        for (final IProject project : projects) {
            ErlangCore.getBackendManager().addExecutionBackend(project, this);
        }
    }

    private void registerStartupFunctionStarter(final BackendData myData) {
        DebugPlugin.getDefault().addDebugEventListener(
                new IDebugEventSetListener() {
                    public void handleDebugEvents(final DebugEvent[] events) {
                        final InitialCall init_call = myData.getInitialCall();
                        if (init_call != null) {
                            runInitial(init_call.getModule(),
                                    init_call.getName(),
                                    init_call.getParameters());
                        }
                        DebugPlugin.getDefault().removeDebugEventListener(this);
                    }
                });
    }

    void runInitial(final String module, final String function,
            final String args) {
        try {
            if (module.length() > 0 && function.length() > 0) {
                ErlLogger.debug("calling startup function %s:%s", module,
                        function);
                if (args.length() > 0) {
                    cast(module, function, "s", args);
                } else {
                    cast(module, function, "");
                }
            }
        } catch (final Exception e) {
            ErlLogger.debug("Could not run initial call %s:%s(\"%s\")", module,
                    function, args);
            ErlLogger.warn(e);
        }
    }

    private void interpretModules(final BackendData myData,
            final boolean distributed) {
        for (final String pm : data.getInterpretedModules()) {
            final String[] pms = pm.split(":");
            final IProject project = ResourcesPlugin.getWorkspace().getRoot()
                    .getProject(pms[0]);
            getDebugHelper()
                    .interpret(this, project, pms[1], distributed, true);
        }
    }

    private void addNodesAsDebugTargets(final ILaunch aLaunch,
            final ErlangDebugTarget target) {
        final OtpErlangList nodes = ErlideDebug.nodes(this);
        if (nodes != null) {
            for (int i = 1, n = nodes.arity(); i < n; ++i) {
                final OtpErlangAtom o = (OtpErlangAtom) nodes.elementAt(i);
                final OtpErlangAtom a = o;
                final ErlangDebugNode edn = new ErlangDebugNode(target,
                        a.atomValue());
                aLaunch.addDebugTarget(edn);
            }
        }
    }

    private void distributeDebuggerCode() {
        final String[] debuggerModules = { "erlide_dbg_debugged",
                "erlide_dbg_icmd", "erlide_dbg_idb", "erlide_dbg_ieval",
                "erlide_dbg_iload", "erlide_dbg_iserver", "erlide_int", "int" };
        final List<OtpErlangTuple> modules = new ArrayList<OtpErlangTuple>(
                debuggerModules.length);
        for (final String module : debuggerModules) {
            final OtpErlangBinary b = getBeam(module);
            if (b != null) {
                final OtpErlangString filename = new OtpErlangString(module
                        + ".erl");
                final OtpErlangTuple t = OtpErlang.mkTuple(new OtpErlangAtom(
                        module), filename, b);
                modules.add(t);
            }
        }
        ErlideDebug.distributeDebuggerCode(this, modules);
    }

    /**
     * Get a named beam-file as a binary from the core plug-in bundle
     * 
     * @param module
     *            module name, without extension
     * @param backend
     *            the execution backend
     * @return
     */
    private OtpErlangBinary getBeam(final String module) {
        final Bundle b = Platform.getBundle("org.erlide.kernel.debugger");
        final String beamname = module + ".beam";
        final IExtensionRegistry reg = RegistryFactory.getRegistry();
        final IConfigurationElement[] els = reg.getConfigurationElementsFor(
                ErlangPlugin.PLUGIN_ID, "codepath");
        // TODO: this code assumes that the debugged target and the
        // erlide-plugin uses the same Erlang version, how can we escape this?
        final String ver = getCurrentVersion();
        for (final IConfigurationElement el : els) {
            final IContributor c = el.getContributor();
            if (c.getName().equals(b.getSymbolicName())) {
                final String dir_path = el.getAttribute("path");
                Enumeration<?> e = b.getEntryPaths(dir_path + "/" + ver);
                if (e == null || !e.hasMoreElements()) {
                    e = b.getEntryPaths(dir_path);
                }
                if (e == null) {
                    ErlLogger.debug("* !!! error loading plugin "
                            + b.getSymbolicName());
                    return null;
                }
                while (e.hasMoreElements()) {
                    final String s = (String) e.nextElement();
                    final Path path = new Path(s);
                    if (path.lastSegment().equals(beamname)) {
                        if (path.getFileExtension() != null
                                && "beam".compareTo(path.getFileExtension()) == 0) {
                            final String m = path.removeFileExtension()
                                    .lastSegment();
                            try {
                                return BeamUtil.getBeamBinary(m, b.getEntry(s));
                            } catch (final Exception ex) {
                                ErlLogger.warn(ex);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private ErlangDebugHelper getDebugHelper() {
        return new ErlangDebugHelper();
    }

    public boolean hasConsole() {
        return getData().hasConsole();
    }

    public BackendData getData() {
        return data;
    }

    public void initialize() {
        shellManager = new BackendShellManager();
        // TODO managed = options.contains(BackendOptions.MANAGED);
        if (isDistributed()) {
            connect();
            final BackendManager bm = ErlangCore.getBackendManager();
            for (final CodeBundle bb : bm.getCodeBundles().values()) {
                register(bb);
            }
            initErlang(data.isMonitored(), data.isManaged());
            // setTrapExit(data.useTrapExit());

            try {
                postLaunch();
            } catch (final DebugException e) {
                e.printStackTrace();
            }
        }
    }

    public void launchRuntime(final BackendData myData) {
        if (launch != null) {
            return;
        }
        final ILaunchConfiguration launchConfig = myData.asLaunchConfiguration();
        try {
            launch = launchConfig.launch(ILaunchManager.RUN_MODE,
                    new NullProgressMonitor(), false, true);
        } catch (final CoreException e) {
            ErlLogger.error(e);
        }
    }

    public String getJavaNodeName() {
        return runtime.getNode().node();
    }
}