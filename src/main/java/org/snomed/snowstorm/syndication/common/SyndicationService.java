package org.snomed.snowstorm.syndication.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.syndication.SyndicationImportService;
import org.snomed.snowstorm.syndication.data.SyndicationImport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.apache.logging.log4j.util.Strings.isNotBlank;
import static org.snomed.snowstorm.core.rf2.rf2import.ImportJob.ImportStatus.COMPLETED;
import static org.snomed.snowstorm.core.rf2.rf2import.ImportJob.ImportStatus.FAILED;
import static org.snomed.snowstorm.core.rf2.rf2import.ImportJob.ImportStatus.RUNNING;
import static org.snomed.snowstorm.core.util.FileUtils.removeDownloadedFiles;
import static org.snomed.snowstorm.syndication.common.SyndicationConstants.LATEST_VERSION;
import static org.snomed.snowstorm.syndication.common.SyndicationConstants.LOCAL_VERSION;

@Service
public abstract class SyndicationService {

    @Autowired
    protected SyndicationImportService importStatusService;

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    public void fetchAndImportTerminology(SyndicationImportParams params) throws IOException, ServiceException, InterruptedException {
        String importedVersion = null;
        try {
            importStatusService.saveOrUpdateImportStatus(params.terminology(), params.version(), importedVersion, RUNNING, null);
            List<File> files = fetchTerminologyPackages(params);
            if(CollectionUtils.isEmpty(files)) {
                throw new ServiceException("No terminology packages found for version" + params.version());
            }
            importTerminology(params, files);
            importedVersion = LOCAL_VERSION.equals(params.version())
                    ? getLocalPackageIdentifier(files)
                    : getTerminologyVersion(files.get(files.size() - 1).getName());
            importStatusService.saveOrUpdateImportStatus(params.terminology(), params.version(), importedVersion, COMPLETED, null);
            if(!LOCAL_VERSION.equals(params.version())) {
                removeDownloadedFiles(files);
            }
        } catch (Exception e) {
            importStatusService.saveOrUpdateImportStatus(params.terminology(), params.version(), importedVersion, FAILED, e.getMessage());
            throw e;
        }
    }


    public boolean alreadyImported(SyndicationImportParams params, SyndicationImport syndicationImport) throws ServiceException, IOException, InterruptedException {
        String terminologyName = params.terminology().getName();
        if (syndicationImport != null && syndicationImport.getStatus() == COMPLETED && isNotBlank(syndicationImport.getActualVersion())) {
            if (syndicationImport.getActualVersion().equals(params.version())) {
                return true;
            }
            if(LOCAL_VERSION.equals(params.version())) {
                String localPackageIdentifier = getLocalPackageIdentifier(fetchTerminologyPackages(params));
                return syndicationImport.getActualVersion().equals(localPackageIdentifier);
            }
            if(LATEST_VERSION.equals(params.version()) || syndicationImport.getActualVersion().contains(params.version())) {
                logger.info("Fetching latest version number for terminology: {}", terminologyName);
                try {
                    String latestVersion = getLatestTerminologyVersion(params.version());
                    logger.info("Latest terminology version: {}", latestVersion);
                    return syndicationImport.getActualVersion().equals(latestVersion);
                }
                catch (Exception e) {
                    logger.error("Failed to fetch latest version number for terminology: {}", terminologyName, e);
                }
            }
        }
        logger.info("Terminology: {} has not yet been imported", terminologyName);
        return false;
    }

    protected String getLocalPackageIdentifier(List<File> localPackages) {
        if(CollectionUtils.isEmpty(localPackages)) {
            return null;
        }
        File file = localPackages.get(localPackages.size() - 1);
        return file.getName() + "-" + file.length() + "-" + file.lastModified();
    }

    protected abstract List<File> fetchTerminologyPackages(SyndicationImportParams params) throws IOException, InterruptedException, ServiceException;

    protected abstract void importTerminology(SyndicationImportParams params, List<File> files) throws IOException, ServiceException, InterruptedException;

    protected abstract String getLatestTerminologyVersion(String params) throws IOException, InterruptedException, ServiceException;

    protected abstract String getTerminologyVersion(String releaseFileName) throws IOException;
}
