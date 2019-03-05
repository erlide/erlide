package org.erlide.engine.services.search;

import org.erlide.runtime.rpc.IRpcResultCallback;
import org.erlide.runtime.rpc.RpcException;

import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;

public interface SearchServerService {

    void startFindRefs(ErlangSearchPattern pattern, ErlSearchScope scope, String stateDir,
            IRpcResultCallback callback, boolean updateSearchServer) throws RpcException;

    OtpErlangObject findRefs(ErlangSearchPattern pattern, ErlSearchScope scope,
            String stateDir, boolean updateSearchServer) throws RpcException;

    void cancelSearch(OtpErlangPid searchDeamonPid) throws RpcException;

}
