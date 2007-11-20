package org.wyona.yarep.util;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Category;

import org.wyona.yarep.core.Node;
import org.wyona.yarep.core.Path;
import org.wyona.yarep.core.Property;
import org.wyona.yarep.core.Repository;
import org.wyona.yarep.core.RepositoryException;
import org.wyona.yarep.core.RepositoryFactory;

/**
 *
 */
public class YarepUtil {

    private static Category log = Category.getInstance(YarepUtil.class);

    /**
     *
     */
    public RepoPath getRepositoryPath(Path path, RepositoryFactory repoFactory) throws RepositoryException {
        Repository repo = null;

        // Determine possible Repository ID. If such a repo ID doesn't exist, then use ROOT repository
        String[] splittedPath = path.toString().split("/");
        if (splittedPath != null) {
            if (splittedPath.length < 2) {
	        log.debug("Length = " + splittedPath.length + ". Use ROOT repository.");
            } else {
                if (repoFactory.exists(splittedPath[1])) {
                    repo = repoFactory.newRepository(splittedPath[1]);
                    log.debug("New Repository: " + repo.getID() + " - " + repo.getName());

                    log.debug("Repo ID length: " + repo.getID().length());
                    path = new Path(path.toString().substring(repo.getID().length() + 1));
                    log.debug("New Path: " + path);
                    return new RepoPath(repo, path);
                } else {
                    log.debug("No such repository \"" + splittedPath[1] + "\". Use ROOT repository.");
                }
            }
        } else {
            log.debug("Path could not be split. Use ROOT repository.");
        }

        // First repository shall be ROOT repository
        repo = repoFactory.firstRepository();
        log.debug("ROOT Repository: " + repo.getID() + " - " + repo.getName());

        log.debug("Path (still original): " + path);
        return new RepoPath(repo, path);
    }
    
    /**
     * Copies the content of one repository into another repository.
     * Currently copies nodes and properties, but no revisions.
     * @param srcRepo repository to be copied
     * @param destRepo assumed to be empty
     * @throws RepositoryException
     */
    public static void copyRepository(Repository srcRepo, Repository destRepo) throws RepositoryException {
        Node srcRootNode = srcRepo.getRootNode(); 
        Node destRootNode = destRepo.getRootNode();
        
        Node[] childNodes = srcRootNode.getNodes();
        for (int i = 0; i < childNodes.length; i++) {
            copyNodeRec(childNodes[i], destRootNode);
        }
    }
    
    /**
     * Adds a copy of the source node as a child of the destination node.
     * Works recursively.
     * @param srcNode
     * @param destParentNode
     * @throws RepositoryException
     */
    protected static void copyNodeRec(Node srcNode, Node destParentNode) throws RepositoryException {
        Node newNode = destParentNode.addNode(srcNode.getName(), srcNode.getType());
        try {
            // copy content:
            if (srcNode.isResource()) {
                OutputStream os = newNode.getOutputStream();
                IOUtils.copy(srcNode.getInputStream(), os);
                os.close();
            }
            // copy properties:
            Property[] properties = srcNode.getProperties();
            for (int i = 0; i < properties.length; i++) {
                newNode.setProperty(properties[i]);
            }
            // recursively copy children
            Node[] childNodes = srcNode.getNodes();
            for (int i = 0; i < childNodes.length; i++) {
                copyNodeRec(childNodes[i], newNode);
            }
        } catch (Exception e) {
            //throw new RepositoryException(e.getMessage(), e);
            log.error("Could not copy node: " + srcNode.getPath() + ": " + e.getMessage(), e);
        }
    }
}
