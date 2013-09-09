package org.erlide.engine;

import org.eclipse.core.resources.IResource;
import org.erlide.engine.model.IErlModel;
import org.erlide.engine.model.ModelSearcherService;
import org.erlide.engine.services.cleanup.CleanupProvider;
import org.erlide.engine.services.codeassist.ContextAssistService;
import org.erlide.engine.services.edoc.EdocExportService;
import org.erlide.engine.services.importer.ImportService;
import org.erlide.engine.services.parsing.ParserService;
import org.erlide.engine.services.parsing.ScannerService;
import org.erlide.engine.services.parsing.SimpleScannerService;
import org.erlide.engine.services.proclist.ProclistService;
import org.erlide.engine.services.search.ModelFindService;
import org.erlide.engine.services.search.ModelUtilService;
import org.erlide.engine.services.search.OpenService;
import org.erlide.engine.services.search.OtpDocService;
import org.erlide.engine.services.search.SearchServerService;
import org.erlide.engine.services.search.XrefService;
import org.erlide.engine.services.text.IndentService;
import org.erlide.runtime.api.IRpcSite;

public interface IErlangEngine {

    @Deprecated
    IRpcSite getBackend();

    IErlModel getModel();

    XrefService getXrefService();

    String getStateDir();

    OpenService getOpenService();

    OtpDocService getOtpDocService();

    IndentService getIndentService();

    ContextAssistService getContextAssistService();

    SearchServerService getSearchServerService();

    ModelUtilService getModelUtilService();

    CleanupProvider getCleanupProvider(final IResource resource);

    ParserService getParserService();

    ScannerService getScannerService(String scannerName, String initialText,
            String path, boolean logging);

    ImportService getImportService();

    EdocExportService getEdocExportService();

    ProclistService getProclistService();

    ScannerService getScannerService(String name);

    SimpleScannerService getSimpleScannerService();

    ModelFindService getModelFindService();

    ModelSearcherService getModelSearcherService();

}
