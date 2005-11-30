package org.apache.cocoon.components.source.impl;

import org.apache.excalibur.source.ModifiableSource;
import org.apache.excalibur.source.Source;
import org.apache.excalibur.source.SourceNotFoundException;
import org.apache.excalibur.source.SourceUtil;
import org.apache.excalibur.source.SourceValidity;
import org.apache.excalibur.source.TraversableSource;
import org.apache.log4j.Category;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.util.Collection;

import org.wyona.yarep.core.Path;
import org.wyona.yarep.core.Repository;
import org.wyona.yarep.core.RepositoryFactory;

/**
 *
 */
public class YarepSource implements ModifiableSource, TraversableSource {

    private static Category log = Category.getInstance(YarepSource.class);

    private Path path;

    private String SCHEME = "yarep";

    private Repository repo;

    /**
     *
     */
    public YarepSource(String src) throws MalformedURLException, Exception {
        if (!SourceUtil.getScheme(src.toString()).equals(SCHEME)) throw new MalformedURLException();

        this.path = new Path(SourceUtil.getSpecificPart(src.toString()));

	log.error("path = " + path);
	log.error("src = " + src);

        // Determine possible Repository ID. If such a repo ID doesn't exist, then use ROOT repository
	String[] splittedPath = path.toString().split("/");
        if (splittedPath != null) {
	    log.error("Length: " + splittedPath.length);
            if (splittedPath.length < 3) {
	        log.error("Length is 0 or 2. Use ROOT repository.");
            } else {
	        log.error("Possible repository ID: " + splittedPath[1]);
                if (new RepositoryFactory().exists(splittedPath[1])) {
                    repo = new RepositoryFactory().newRepository(splittedPath[1]);
                    path = path; // TODO: repo needs to be removed
                }
            }
        } else {
            log.error("Path could not be split. Use ROOT repository.");
        }

        repo = new RepositoryFactory().firstRepository();
    }

    /**
     *
     */
    public boolean exists() {
        return repo.exists(path);
    }

    /**
     *
     */
    public long getContentLength() {
        log.warn("getContentLength() not implemented yet!");
        return System.currentTimeMillis();
    }

    /**
     *
     */
    public InputStream getInputStream() throws IOException, SourceNotFoundException {
        return repo.getInputStream(path);
    }

    /**
     *
     */
    public long getLastModified() {
        //return repo.getLastModified(path);
        return System.currentTimeMillis();
    }

    /**
     *
     */
    public String getMimeType() {
        log.warn("getMimeType() not implemented yet!");
        return null;
    }

    /**
     *
     */
    public String getScheme() {
        return SCHEME;
    }

    /**
     *
     */
    public String getURI() {
        log.warn("getURI() not really implemented yet! Path: " + path);
        return SCHEME + ":" + path.toString();
    }

    /**
     *
     */
    public SourceValidity getValidity() {
        log.warn("getValidity() not implemented yet!");
        return null;
    }

    /**
     *
     */
    public void refresh() {
        log.warn("Not implemented yet!");
    }

    /**
     *
     */
    public boolean canCancel(OutputStream out) {
        log.warn("Not implemented yet!");
        return false;
    }

    /**
     *
     */
    public void cancel(OutputStream out) {
        log.warn("Not implemented yet!");
    }

    /**
     *
     */
    public void delete() {
        log.warn("Not implemented yet!");
    }

    /**
     *
     */
    public OutputStream getOutputStream() throws IOException {
        return repo.getOutputStream(path);
    }

    /**
     *
     */
    public Source getParent() {
        log.warn("Not implemented yet!");
        return null;
    }

    /**
     *
     */
    public String getName() {
        return path.getName();
    }

    /**
     *
     */
    public Source getChild(String name) {
        log.warn("Not implemented yet!");
        return null;
    }

    /**
     *
     */
    public Collection getChildren() {
        Path[] children = repo.getChildren(path);
        java.util.Vector collection = new java.util.Vector();
        try {
            for (int i = 0; i < children.length; i++) {
                collection.add(new YarepSource("yarep:" + children[i].toString()));
            }
        } catch (MalformedURLException e) {
            log.error(e);
        } catch (Exception e) {
            log.error(e);
        }
        return collection;
    }

    /**
     *
     */
    public boolean isCollection() {
        return repo.isCollection(path);
    }
}
