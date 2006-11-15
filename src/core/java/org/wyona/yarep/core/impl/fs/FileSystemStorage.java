package org.wyona.yarep.core.impl.fs;

import org.wyona.commons.io.FileUtil;
import org.wyona.yarep.core.NoSuchNodeException;
import org.wyona.yarep.core.Path;
import org.wyona.yarep.core.Storage;
import org.wyona.yarep.core.UID;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;

import org.apache.avalon.framework.configuration.Configuration;
import org.apache.log4j.Category;

/**
 *
 */
public class FileSystemStorage implements Storage {

    private static Category log = Category.getInstance(FileSystemStorage.class);

    private File contentDir;

    /**
     *
     */
    public void readConfig(Configuration storageConfig, File repoConfigFile) throws Exception {
        Configuration contentConfig = storageConfig.getChild("content", false);
        try {
            contentDir = new File(contentConfig.getAttribute("src"));
            log.debug(contentDir.toString());
            log.debug(repoConfigFile.toString());
            if (!contentDir.isAbsolute()) {
                contentDir = FileUtil.file(repoConfigFile.getParent(), contentDir.toString());
            }
            log.debug(contentDir.toString());
            //TODO: Throw an exception
            if (!contentDir.exists()) log.error("No such file or directory: " + contentDir);
        } catch (Exception e) {
            log.error(e);
            throw e;
        }
    }

    /**
     *@deprecated
     */
    public Writer getWriter(UID uid, Path path) {
        return new FileSystemRepositoryWriter(uid, path, contentDir);
    }

    /**
     *
     */
    public OutputStream getOutputStream(UID uid, Path path) throws IOException {
        return new FileSystemRepositoryOutputStream(uid, path, contentDir);
    }

    /**
     *@deprecated
     */
    public Reader getReader(UID uid, Path path) throws NoSuchNodeException {
        return new FileSystemRepositoryReader(uid, path, contentDir);
    }

    /**
     *
     */
    public InputStream getInputStream(UID uid, Path path) throws IOException {
        return new FileSystemRepositoryInputStream(uid, path, contentDir);
    }

    /**
     *
     */
    public long getLastModified(UID uid, Path path) {
        File file = new File(contentDir.getAbsolutePath() + File.separator + uid.toString());
        return file.lastModified(); 
    }

    /**
     *
     */
    public boolean delete(UID uid, Path path) {
        File file = new File(contentDir.getAbsolutePath() + File.separator + uid.toString());
        log.debug("Try to delete: " + file);
        return file.delete(); 
    }
}
