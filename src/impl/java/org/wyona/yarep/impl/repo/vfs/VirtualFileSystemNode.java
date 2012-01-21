package org.wyona.yarep.impl.repo.vfs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.wyona.yarep.core.NoSuchRevisionException;
import org.wyona.yarep.core.Node;
import org.wyona.yarep.core.NodeStateException;
import org.wyona.yarep.core.NodeType;
import org.wyona.yarep.core.Path;
import org.wyona.yarep.core.Property;
import org.wyona.yarep.core.PropertyType;
import org.wyona.yarep.core.RepositoryException;
import org.wyona.yarep.core.Revision;
import org.wyona.yarep.core.UID;
import org.wyona.yarep.core.attributes.VersionableV1;
import org.wyona.yarep.core.impl.vfs.SplitPathConfig;
import org.wyona.yarep.impl.AbstractNode;
import org.wyona.yarep.impl.DefaultProperty;

/**
 * This class represents a repository node.
 * A repository node may be either a collection ("directory") or a resource ("file").
 */
public class VirtualFileSystemNode extends AbstractNode implements VersionableV1 {
    private static Logger log = Logger.getLogger(VirtualFileSystemNode.class);

    protected static final String META_FILE_NAME = "meta";
    protected static final String REVISIONS_BASE_DIR = "revisions";
    protected static final String META_DIR_SUFFIX = ".yarep";
    private static final char PROPERTY_SEPARATOR = ':';
    
    //protected FileSystemRepository repository;
    protected File contentDir;
    protected File contentFile;
    protected File metaDir;
    protected File metaFile;

    // INFO: Because of the new split path functionality previous versions with a regular path are migrated automatically to a splitted path version, whereas the original version remains as a backup (TODO: Make the backup configurable, because one might have no need for backups)
    protected File backupContentFile;
    protected File backupMetaDir;
    protected File backupMetaFile;
    
    // NOTE: Flag to indicate if revisions already have been read/initialized from file system
    protected boolean areRevisionsRead = false;
    
    protected RevisionDirectoryFilter revisionDirectoryFilter = new RevisionDirectoryFilter();

    String vfsMetaFileVersion = null;
    
    /**
     * Constructor
     * @throws RepositoryException
     */
    public VirtualFileSystemNode(VirtualFileSystemRepository repository, String path, String uuid) throws RepositoryException {
        super(repository, path, uuid);
        init();
    }
    
    /**
     * Constructor
     * @throws RepositoryException
     */
    protected VirtualFileSystemNode(VirtualFileSystemRepository repository, String path, String uuid, boolean doInit) throws RepositoryException {
        super(repository, path, uuid);
        
        if (doInit) {
            init();
        }
    }
    
    /**
     * Init node
     */
    protected void init() throws RepositoryException {
        
        this.contentDir = getRepository().getContentDir();
        this.contentFile = new File(this.contentDir, SplitPathConfig.splitPathIfRequired(this.uuid, getRepository().getSplitPathConfig()));
        this.backupContentFile = new File(this.contentDir, this.uuid);
        
        String metauuid = SplitPathConfig.splitPathIfRequired(uuid + META_DIR_SUFFIX, getRepository().getSplitPathConfig());
        if (getRepository().getMetaDir() != null) {
            this.metaDir = new File(getRepository().getMetaDir(), metauuid);
            this.backupMetaDir = new File(getRepository().getMetaDir(), uuid + META_DIR_SUFFIX);
        } else {
            this.metaDir = new File(this.contentDir, metauuid);
            this.backupMetaDir = new File(this.contentDir, uuid + META_DIR_SUFFIX);
        }
        this.metaFile = new File(this.metaDir, META_FILE_NAME);
        this.backupMetaFile = new File(this.backupMetaDir, META_FILE_NAME);
        
        if (log.isDebugEnabled()) {
            log.debug("VirtualFileSystemNode: path=" + path + " uuid=" + uuid);
            log.debug("contentDir=" + contentDir);
            log.debug("contentFile=" + contentFile);
            log.debug("metaDir=" + metaDir);
            log.debug("metaFile=" + metaFile);
        }
        
        if (!metaFile.exists()) {
            // TODO: Is this really necessary?
            log.warn("No meta file exists yet for node '" + path + "' and hence one will be created!");
            createMetaFile();
        }
        readProperties();
        // defer reading of revisions for performance reasons
        // readRevisions();
    }

    /**
     * Create meta file
     */
    protected void createMetaFile() throws RepositoryException {
        log.debug("creating new meta file in dir: " + metaDir);
        if (!metaDir.exists()) {
            metaDir.mkdirs();
        }
        this.properties = new HashMap();
        if (this.contentFile.isDirectory()) {
            this.setProperty(PROPERTY_TYPE, NodeType.TYPENAME_COLLECTION);
        } else {
            this.setProperty(PROPERTY_TYPE, NodeType.TYPENAME_RESOURCE);
            //this.setProperty(PROPERTY_SIZE, this.contentFile.length());
        }
        this.setProperty(PROPERTY_LAST_MODIFIED, this.metaFile.lastModified());
    }
    
    /**
     * Read properties from meta file
     */
    protected void readProperties() throws RepositoryException {
        try {
            log.debug("Reading meta file: " + this.metaFile);
            this.properties = new HashMap();
            this.vfsMetaFileVersion = null;
            BufferedReader reader = new BufferedReader(new FileReader(this.metaFile));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                String name;
                String typeName;
                String value;

                if (vfsMetaFileVersion != null && vfsMetaFileVersion.equals("1.0")) {
                    try {
                        name = unescapeSeparator(line.substring(0, line.indexOf("<")).trim());
                        typeName = line.substring(line.indexOf("<")+1, line.indexOf(">")).trim();

                        value = unescapeLinebreak(unescapeSeparator(line.substring(getValueStartIndex(line)).trim()));
                    } catch (StringIndexOutOfBoundsException e) {
                        throw new RepositoryException("Error while parsing meta file: " + this.metaFile + " at line " + line);
                    }
                } else { // INFO: Backwards compatibility (also see method checkForSeparator(String))
                    try {
                        name = unescapeSeparator(line.substring(0, line.indexOf("<")).trim());
                        typeName = line.substring(line.indexOf("<")+1, line.indexOf(">")).trim();
                        value = unescapeLinebreak(line.substring(line.indexOf(PROPERTY_SEPARATOR) + 1).trim());
                        // INFO: Because revisions of a node also contain separators, the checkForSeparator() method generates a huge amount of log entries!
                        //value = unescapeLinebreak(checkForSeparator(line.substring(line.indexOf(PROPERTY_SEPARATOR) + 1).trim()));
                    } catch (StringIndexOutOfBoundsException e) {
                        throw new RepositoryException("Error while parsing meta file: " + this.metaFile + " at line " + line);
                    }
                }

                if (name.equals("yarep_vfs-meta-file-version")) {
                    vfsMetaFileVersion = value;
                }

                Property property = new DefaultProperty(name, PropertyType.getType(typeName), this);
                property.setValueFromString(value);
                this.properties.put(name, property);
            }
            reader.close();
        } catch (IOException e) {
            throw new RepositoryException("Error while reading meta file: " + metaFile + ": " 
                    + e.getMessage());
        }
    }

    /**
     * @depecated Use {@link saveProperties(String)} instead
     * Save all properties within a meta file
     * @throws RepositoryException
     */
/*
    protected void saveProperties() throws RepositoryException {
        log.warn("DEPRECATED");
        try {
            log.debug("Writing meta file: " + this.metaFile);
            PrintWriter writer = new PrintWriter(new FileOutputStream(this.metaFile));

            writer.println("yarep_vfs-meta-file-version" + "<" + "string" + ">" + PROPERTY_SEPARATOR + "1.0");
            if (vfsMetaFileVersion != null && !vfsMetaFileVersion.equals("1.0")) {
                throw new RepositoryException("No such vfs meta file version supported: " + vfsMetaFileVersion);
            }
            
            Iterator iterator = this.properties.values().iterator();
            while (iterator.hasNext()) {
                Property property = (Property)iterator.next();
                if (!property.getName().equals("yarep_vfs-meta-file-version")) {
                    if (property.getValueAsString() == null) {
                        log.warn("Value as string of property '" + property.getName() + "' is null!");
                    }
                    writer.println(escapeSeparator(property.getName()) + "<" + PropertyType.getTypeName(property.getType()) + ">" + PROPERTY_SEPARATOR + escapeLinebreak(escapeSeparator(property.getValueAsString())));

                    // NOTE: Please note that the property is being indexed before it is being saved persistently. Does that make sense?
//                    if (getRepository().isAutoPropertyIndexingEnabled()) {
//                        log.debug("Index property '" + property.getName() + "' of node: " + this.getPath());
//                        getRepository().getIndexer().index(this, property);
//                    }
                }
            }
            writer.flush();
            writer.close();
        } catch (Exception e) {
            log.error(e, e);
            throw new RepositoryException("Error while writing meta file: " + metaFile + ": " + e.getMessage(), e);
        }
    }
*/

    /**
     * Save all properties within a meta file
     * TODO: Changing a property should update the last modified date. This implementation does not change the last modified date if a property changes.
     * @param name Property name which has been set or removed (see setProperty(Property) and removeProperty(String))
     * @throws RepositoryException
     */
    private void saveProperties(String name) throws RepositoryException {
        try {
            log.debug("Writing meta file: " + this.metaFile);
            PrintWriter writer = new PrintWriter(new FileOutputStream(this.metaFile));

            writer.println("yarep_vfs-meta-file-version" + "<" + "string" + ">" + PROPERTY_SEPARATOR + "1.0");
            if (vfsMetaFileVersion != null && !vfsMetaFileVersion.equals("1.0")) {
                throw new RepositoryException("No such vfs meta file version supported: " + vfsMetaFileVersion);
            }

            Property modifiedProperty = null;
            Iterator iterator = this.properties.values().iterator();
            while (iterator.hasNext()) {
                Property property = (Property)iterator.next();
                if (!property.getName().equals("yarep_vfs-meta-file-version")) {
                    if (property.getValueAsString() == null) {
                        log.warn("Value as string of property '" + property.getName() + "' is null!");
                    } else {
                        if (property.getName().equals(name)) {
                            modifiedProperty = property;
                        }
                    }
                    writer.println(escapeSeparator(property.getName()) + "<" + PropertyType.getTypeName(property.getType()) + ">" + PROPERTY_SEPARATOR + escapeLinebreak(escapeSeparator(property.getValueAsString())));
                }
            }
            writer.flush();
            writer.close();

/* NOTE: Already done by setProperty(Property) and removeProperty(String)
            if (modifiedProperty != null) {
                if (getRepository().isAutoPropertyIndexingEnabled()) {
                    log.debug("Index property '" + modifiedProperty.getName() + "' of node: " + this.getPath());
                    getRepository().getIndexer().index(this, modifiedProperty);
                }
            } else {
                log.warn("Modified property '" + name + "' has not been indexed!");
            }
*/
        } catch (Exception e) {
            log.error(e, e);
            throw new RepositoryException("Error while writing meta file: " + metaFile + ": " + e.getMessage(), e);
        }
    }
    
    public Node[] getNodes() throws RepositoryException {
        log.debug("+++ Node.getNodes() was called! Node Path = "+getPath());
        return getNodes(null);
    }

    public Node[] getNodes(FileFilter fileFilter) throws RepositoryException {
        Node[] childNodes = new Node[0];

        List<String> listOfPaths = getNodesAsPathStrings(fileFilter);

        if (!listOfPaths.isEmpty()) {
            log.warn("Node[] getNodes(): Converting all Node paths into Node objects and putting them into an array");
            childNodes = new Node[listOfPaths.size()];
            int i = 0;
            for (String path: listOfPaths) {
                childNodes[i] = this.repository.getNode(path);
                i++;
            }
            log.warn("Getting all "+childNodes.length+" children nodes: DONE");

        } 

        return childNodes;
    }

    public List<String> getNodesAsPathStrings(FileFilter fileFilter) throws RepositoryException {
        List<String> childPaths = new ArrayList<String>();
        Path[] nonSplitChildren = null;
        if (getRepository().isSplitPathEnabled()) {
            
            File startingDirectory = new File(contentDir + path);
            log.debug("startingDirectory= "+startingDirectory);
            if (!startingDirectory.exists()) {
                log.debug("No such file or directory: " + startingDirectory);
                //childPaths = new Path[0];
                
            } else if (startingDirectory.isFile()) {
                log.debug("Can not get children form a file! : " + startingDirectory);
                //childPaths = new Path[0];
            } else {
                // only if the current path belongs to a "include" path for the split paths, we search ALL children, otherwise just the direct children
                log.debug("Checking whether this path is include: "+path);
                if (!SplitPathConfig.isIncludePath(path, getRepository().getSplitPathConfig())) {
                    log.warn("Path is not split: "+path);
                    nonSplitChildren = getRepository().getMap().getChildren(new Path(path));
                    
                } else {
                    log.warn("+++ Path is split! Path = "+path+" --> Calling getAllFiles()...");
                    long start = System.currentTimeMillis();
                    List<File> allChildren = getAllFiles(startingDirectory, fileFilter); // get all children from the location "this.path"
                    log.warn("+++ getAllFiles() took "+(System.currentTimeMillis()-start)+"ms");
                    log.warn("+++ Number of children in split path: " + allChildren.size() + " (" + startingDirectory + ")");
                    List<String> validChildrenPaths = new ArrayList<String>();
                    String fileSepForRegEx = File.separator;
                    if (fileSepForRegEx.equals("\\")) {
                        fileSepForRegEx = "\\\\"; // this is a double backslash, used for the regex later
                    }
                    
                    for (File child: allChildren) {
                        String childFullPath = child.getAbsolutePath().replaceAll(fileSepForRegEx, "/"); // whatever the file separator was, yarep uses "/"
                        log.debug("startingDirectory= "+startingDirectory);
                        log.debug("child = "+child.getAbsolutePath());
                        if (childFullPath.startsWith(contentDir.getAbsolutePath())) {
                            String pathAndNode = childFullPath.substring(contentDir.getAbsolutePath().length()); // = starts with this.path, e.g. "/path/node"
                            log.debug("1. pathAndNode = "+pathAndNode);
                            String unsplitPathAndNode = SplitPathConfig.unsplitPathIfRequired(pathAndNode, getRepository().getSplitPathConfig());
                            String unsplitNode = unsplitPathAndNode.substring(path.length()); // we remove the path (prefix) from e.g. "/path/node.xml" -> "/node.xml"
                            log.debug("2. unsplitNode = "+unsplitNode);
                            boolean ignore = ((org.wyona.yarep.impl.VFileSystemMapImpl) getRepository().getMap()).ignorePath(unsplitNode);
                            if (!ignore) {
                                String newPath = null;
                                if (path.equals(File.separator)) {
                                    newPath = unsplitNode;
                                    if (!newPath.startsWith("/")) {
                                        newPath = "/" + newPath;
                                    }
                                    log.debug("path was File separator. newPath = "+newPath);
                                    
                                } else if (path.toString().endsWith(File.separator) || path.toString().endsWith("/")) {
                                    newPath = path.substring(0,path.length()-1);
                                    if (!unsplitNode.startsWith("/")) {
                                        unsplitNode = "/" + unsplitNode;
                                    }
                                    newPath = path + unsplitNode;
                                    log.debug("path ended with a /. newPath = "+newPath.toString());
                                } else {
                                    // NOTE: Do not use File.separator here, because it's the repository path and not the Operating System's File System path
                                    if (!unsplitNode.startsWith("/")) {
                                        unsplitNode = "/" + unsplitNode;
                                    }
                                    newPath = path + unsplitNode;
                                    log.debug("path does not end with a /. newPath = "+newPath.toString());
                                }
                                validChildrenPaths.add(newPath);
                                log.debug("getNodes('"+path+"'): ADDED: "+unsplitNode);
                           } else {
                               log.warn("getNodes('"+path+"'): IGNORED: "+unsplitNode);
                           }
                         } else {
                            log.error("Something is wrong: children are not within parents!");
                        }
                    }
                    log.warn("=== All children found: "+validChildrenPaths.size()+" ===");
                    //childPaths = validChildrenPaths.toArray(new Path[validChildrenPaths.size()]);
                    childPaths = validChildrenPaths;
                } // end if-else: include or not
                
            }
            

        } else {
            // split path is not enabled
            nonSplitChildren = getRepository().getMap().getChildren(new Path(this.path));
        }
        
        if (childPaths.isEmpty()) {
            if (nonSplitChildren != null) {
                for (Path path: nonSplitChildren) {
                    childPaths.add(path.toString());
                }
            }
        }
        return childPaths;
    }

    /**
     * 
     * @param dir directory to get all files from
     * @param fileFilter you can pass in NULL if you don't want to filter for certain file patterns
     * @return List of Files (directories are NOT in the list!)
     */
    private List<File> getAllFiles(File dir, FileFilter fileFilter) {
        List<File> result = new ArrayList<File>();
        if (dir.isDirectory()) {
            long start = System.currentTimeMillis();
            File[] filesAndDirsArray = null;
            if (fileFilter != null) {
                filesAndDirsArray = dir.listFiles(fileFilter);
            } else {
                filesAndDirsArray = dir.listFiles();
            }
            // A warning shall be logged if something takes longer than 10 seconds!
            long delta = System.currentTimeMillis()-start;
            if (delta > 10000) {
                log.warn("List created in "+(System.currentTimeMillis()-start)+"ms"+" : "+dir.getAbsolutePath());
            }
            
            start = System.currentTimeMillis();
            List<File> filesAndDirs = Arrays.asList(filesAndDirsArray);
            start = System.currentTimeMillis();
            for (File file : filesAndDirs) {
                if (file.isFile()) {
                    result.add(file); // because of the xmlFilter above, only xml files in the list
                } else {
                    List<File> deeperList = getAllFiles(file, fileFilter);
                    result.addAll(deeperList);
                }
            }
            // A warning shall be logged if something takes longer than 10 seconds!
            delta = System.currentTimeMillis()-start;
            if (delta > 10000) {
                log.warn("Looping took "+(System.currentTimeMillis()-start)+"ms"+" : "+dir.getAbsolutePath());
            }
        }
        return result;
    }

    /**
     * @see org.wyona.yarep.core.Node#addNode(java.lang.String, int)
     */
    public Node addNode(String name, int type) throws RepositoryException {
        String newPath = getPath() + "/" + name;
        if (getPath().endsWith("/")) {
            newPath = getPath() + name;
        }
        log.debug("adding node: " + newPath);
        if (this.repository.existsNode(newPath)) {
            throw new RepositoryException("Node exists already: " + newPath);
        }
        UID uid = getRepository().getMap().create(new Path(SplitPathConfig.splitPathIfRequired(newPath, getRepository().getSplitPathConfig())), type);
        // create file:
        File file = new File(this.contentDir, uid.toString());
        try {
            if (type == NodeType.COLLECTION) {
                file.mkdirs();
            } else if (type == NodeType.RESOURCE) {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } else {
                throw new RepositoryException("Unknown node type: " + type);
            }
            return this.repository.getNode(newPath);
        } catch (IOException e) {
            throw new RepositoryException("Could not access file " + file, e);
        }
    }
    
    /**
     * @see org.wyona.yarep.core.Node#removeProperty(java.lang.String)
     */
    public void removeProperty(String name) throws RepositoryException {
        this.properties.remove(name);
        //saveProperties();
        saveProperties(name);

        try {
            if (getRepository().isAutoPropertyIndexingEnabled()) {
                log.debug("Remove property '" + name + "' of node: " + this.getPath() + " from index.");
                getRepository().getIndexer().removeFromIndex(this, getProperty(name));
            }
        } catch(Exception e) {
            log.error(e, e);
        }
    }
    
    /**
     * @see org.wyona.yarep.core.Node#setProperty(org.wyona.yarep.core.Property)
     */
    public void setProperty(Property property) throws RepositoryException {
        //log.debug("Set property: " + property.getName());
        this.properties.put(property.getName(), property);
        //saveProperties();
        saveProperties(property.getName());

        try {
            if (getRepository().isAutoPropertyIndexingEnabled()) {
                log.debug("Index property '" + property.getName() + "' of node: " + this.getPath());
                getRepository().getIndexer().index(this, property);
            }
        } catch(Exception e) {
            log.error(e, e);
        }
    }

    /**
     * @see org.wyona.yarep.core.Node#getInputStream()
     */
    public InputStream getInputStream() throws RepositoryException {
        try {
            if (isCollection()) {
                if (getRepository().getAlternative() != null) {
                    File backupAlternativeFile = new File(backupContentFile, getRepository().getAlternative());
                    File alternativeFile = new File(contentFile, getRepository().getAlternative());

                    if (!alternativeFile.exists() && backupAlternativeFile.exists()) {
                        if (alternativeFile.isFile()) {
                            alternativeFile.getParentFile().mkdirs();
                            try {
                                IOUtils.copy(new FileInputStream(backupAlternativeFile), new FileOutputStream(alternativeFile));
                            } catch (IOException e) {
                                log.error("Could not copy file or directory '" + backupAlternativeFile + "' to '" + alternativeFile + "'");
                                log.error(e, e);
                            }
                        }
                    }

                    if (alternativeFile.isFile()) {
                        return new FileInputStream(alternativeFile);
                    } else {
                        log.warn("Is Collection (" + contentFile + ") and no alternative File exists (" + alternativeFile + ")");
                        return new java.io.StringBufferInputStream(getDirectoryListing(contentFile, getRepository().getDirListingMimeType()));
                    }
                } else {
                    log.warn("Is Collection: " + contentFile);
                    return new java.io.StringBufferInputStream(getDirectoryListing(contentFile, getRepository().getDirListingMimeType()));
                }
            } else {
                if (!contentFile.exists() && backupContentFile.exists()) {
                    log.warn("Not-splitted-yet file exists, hence copying file " + backupContentFile.getAbsolutePath() + " to " + contentFile.getAbsolutePath());
                    contentFile.getParentFile().mkdirs();
                    try {
                        IOUtils.copy(new FileInputStream(backupContentFile), new FileOutputStream(contentFile));
                    } catch (IOException e) {
                        log.error("Could not copy file or directory '" + backupContentFile + "' to '" + contentFile + "'");
                        log.error(e, e);
                    }
                }
                return new FileInputStream(contentFile);
            }
        } catch (FileNotFoundException e) {
            throw new RepositoryException(e.getMessage(), e);
        }
        //return getProperty(PROPERTY_CONTENT).getInputStream();
    }
    
    /**
     * @see org.wyona.yarep.core.Node#getOutputStream()
     */
    public OutputStream getOutputStream() throws RepositoryException {
        try {
            if (isCollection()) {
                if (getRepository().getAlternative() != null) {
                    File alternativeFile = new File(contentFile, getRepository().getAlternative());
                    if (alternativeFile.isFile()) {
                        return new VirtualFileSystemOutputStream(this, alternativeFile);
                    } else {
                        throw new RepositoryException("Is not a file: " + alternativeFile);
                    }
                } else {
                    throw new RepositoryException("Is a directory: " + this.contentFile);
                }
            } else {
                //return new FileOutputStream(this.contentFile);
                log.debug("Write content to '" + contentFile + "'");
                return new VirtualFileSystemOutputStream(this, this.contentFile);
            }
        } catch (FileNotFoundException e) {
            throw new RepositoryException(e.getMessage(), e);
        }
        //return getProperty(PROPERTY_CONTENT).getOutputStream();
    }
    
    /**
     * @see org.wyona.yarep.core.Node#checkin()
     */
    public Revision checkin() throws NodeStateException, RepositoryException {
        return checkin("");
    }

    /**
     * @see org.wyona.yarep.core.Node#checkin()
     */
    public Revision checkin(String comment) throws NodeStateException, RepositoryException {
        return doCheckin(comment, System.currentTimeMillis());
    }

    /**
     * Checkin node and create a revision for a particular time, which can be used to manipulate the history of a node. This method is not part of the Yarep API, but one needs to cast this class in order to use it.
     * @param time A particular revision time
     * @return revision which has been created or manipulated
     */
    public Revision checkin(String comment, long time) throws NodeStateException, RepositoryException {
        log.warn("Create/manipulate a revision '" + getPath() + "' for a particular time: " + new Date(time) + " (Epoch time value: " + time + ")");
        return doCheckin(comment, time);
    }

    /**
     * Checkin node and create a revision for a particular time
     * @param time A particular revision time
     * @return revision which has been created
     */
    private Revision doCheckin(String comment, long time) throws NodeStateException, RepositoryException {
        if (!isCheckedOut()) {
            throw new NodeStateException("Node " + path + " is not checked out.");
        }

        Revision revision = createRevision(comment, time);
        
        setProperty(PROPERTY_IS_CHECKED_OUT, false);
        setProperty(PROPERTY_CHECKIN_DATE, new Date(time));
        setProperty(PROPERTY_LAST_MODIFIED, time); // INFO: Overwrite createMetaFile()
        
        return revision;
    }
    
    /**
     * @see org.wyona.yarep.core.Node#cancelCheckout()
     */
    public void cancelCheckout() throws NodeStateException, RepositoryException {
        if (!isCheckedOut()) {
            throw new NodeStateException("Node " + path + " is not checked out.");
        }
        
        setProperty(PROPERTY_IS_CHECKED_OUT, false);
        setProperty(PROPERTY_CHECKIN_DATE, new Date());
    }
    

    /**
     * @see org.wyona.yarep.core.Node#checkout(java.lang.String)
     */
    public void checkout(String userID) throws NodeStateException, RepositoryException {
        // TODO: this should be somehow synchronized
        if (isCheckedOut()) {
            throw new NodeStateException("Node " + path + " is already checked out by: " + getCheckoutUserID());
        }
        
        setProperty(PROPERTY_IS_CHECKED_OUT, true);
        setProperty(PROPERTY_CHECKOUT_USER_ID, userID);
        setProperty(PROPERTY_CHECKOUT_DATE, new Date()); // TODO: One should be able to overwrite the checkout date similar to as the checkout method is able to do so.

        /*if (getRevisions().length == 0) {
            // create a backup revision
            createRevision("initial revision");
        }*/
    }

    /**
     * Create revision of this node
     * @param comment Comment re this new revision
     */
    protected Revision createRevision(String comment) throws RepositoryException {
        return createRevision(comment, System.currentTimeMillis());
    }

    /**
     * Create revision of this node for a particular time, TODO
     * @param comment Comment re this new revision
     * @param revisionTime A particular time
     */
    private Revision createRevision(String comment, long revisionTime) throws RepositoryException {
        if (!areRevisionsRead) {
            readRevisions();
        }
        try {
            String revisionName = String.valueOf(revisionTime);

            File destContentFile = getRevisionContentFile(revisionName);
            log.debug("Create revision '" + destContentFile + "' for a particular time: " + new Date(revisionTime));
            FileUtils.copyFile(this.contentFile, destContentFile);
        
            File destMetaFile = getRevisionMetaFile(revisionName);
            FileUtils.copyFile(this.metaFile, destMetaFile);
        
            Revision revision = new VirtualFileSystemRevision(this, revisionName);
            revision.setProperty(PROPERTY_IS_CHECKED_OUT, false);
            ((VirtualFileSystemRevision)revision).setCreationDate(new Date(revisionTime));
            ((VirtualFileSystemRevision)revision).setCreator(getCheckoutUserID());
            ((VirtualFileSystemRevision)revision).setComment(comment);
            this.revisions.put(revisionName, revision);

            DateIndexerSearcher dis = new DateIndexerSearcher(this, this.metaDir);
            try {
                dis.addRevision(revisionName);
            } catch(Exception e) {
                log.error(e, e);
            }

            return revision;
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new RepositoryException(e.getMessage(), e);
        }
    }
    
    /**
     * Read revisions
     */
    protected void readRevisions() throws RepositoryException {
        File revisionsBaseDir = new File(this.metaDir, REVISIONS_BASE_DIR);
        if (log.isDebugEnabled()) log.debug("Read revisions: " + revisionsBaseDir);
        
        File[] revisionDirs = revisionsBaseDir.listFiles(this.revisionDirectoryFilter);
        
        this.revisions = new LinkedHashMap();
        
        if (revisionDirs != null) {
            if (log.isDebugEnabled()) log.debug("Number of revisions which made it through the filter: " + revisionDirs.length);
            Arrays.sort(revisionDirs);
            for (int i = 0; i < revisionDirs.length; i++) {
                String revisionName = revisionDirs[i].getName();
                Revision revision = new VirtualFileSystemRevision(this, revisionName);
                this.revisions.put(revisionName, revision);
            }
        }
        areRevisionsRead = true;
    }
    
    /**
     * @see org.wyona.yarep.core.Node#restore(java.lang.String)
     */
    public void restore(String revisionName) throws NoSuchRevisionException, RepositoryException {
        try {
            File revisionsBaseDir = new File(this.metaDir, REVISIONS_BASE_DIR);
            File revisionDir = new File(revisionsBaseDir, revisionName);
            
            File srcContentFile = new File(revisionDir, VirtualFileSystemRevision.CONTENT_FILE_NAME);
            FileUtils.copyFile(srcContentFile, this.contentFile);
        
            File srcMetaFile = new File(revisionDir, META_FILE_NAME);
            FileUtils.copyFile(srcMetaFile, this.metaFile);
            
            setProperty(AbstractNode.PROPERTY_LAST_MODIFIED, this.contentFile.lastModified());
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new RepositoryException(e.getMessage(), e);
        }
    }
    
    /**
     * Check if file is a revision
     */
    protected class RevisionDirectoryFilter implements FileFilter {
        public boolean accept(File pathname) {
            if (pathname.getName().matches("[0-9]+") && pathname.isDirectory()) {
                return true;
            } else {
                if (!pathname.getName().startsWith(".")) { // Ignore hidden files
                    log.warn("Does not seem to be a revision: " + pathname);
                }
                return false;
            }
        }
    }
    
    /**
     * @see org.wyona.yarep.impl.AbstractNode#getLastModified()
     */
    @Override
    public long getLastModified() throws RepositoryException {
        long lm = super.getLastModified();
        if (lm > 0) {
            return lm;
        } else {
            return this.contentFile.lastModified();
        }
    }
    
    /**
     * @see org.wyona.yarep.impl.AbstractNode#getSize()
     */
    public long getSize() throws RepositoryException {
        return this.contentFile.length();
    }

    /**
     * Get the virtual file system repository this node belongs to
     */
    public VirtualFileSystemRepository getRepository() {
        return (VirtualFileSystemRepository)this.repository;
    }

    /**
     * @see org.wyona.yarep.core.Node#delete()
     */
    public void delete() throws RepositoryException {
        deleteRec();
    }

    /**
     * Delete node and its children recursively
     */
    protected void deleteRec() throws RepositoryException {

        Node[] children = getNodes();
        if (children.length > 0) {
            log.debug("Try to delete '" + children.length + "' children of node: " + getPath());
            for (int i=0; i < children.length; i++) {
                children[i].delete();
            }
        } else {
            log.debug("Node '" + getPath() + "' does not seem to have any children.");
        } 

        Property[] props = getProperties();
        //boolean success = getRepository().getMap().delete(new Path(getPath()));
        try {
            if (getRepository().getMap().isCollection(new Path(getPath()))) {
                FileUtils.deleteDirectory(this.contentFile);
            } else {
                this.contentFile.delete();
            }
            FileUtils.deleteDirectory(this.metaDir);
        } catch (IOException e) {
            throw new RepositoryException("Could not delete node: " + getPath() + ": " + e.toString(), e);
        }

        // INFO: Delete node from fulltext search index
        try {
            // INFO: Remove from fulltext index
            getRepository().getIndexer().removeFromIndex(this);

            // INFO: Remove from properties index
            if (getRepository().isAutoPropertyIndexingEnabled()) {
                for (int i = 0; i < props.length; i++) {
                    log.debug("Remove property '" + props[i].getName() + "' of node: " + getPath() + " from index.");
                    getRepository().getIndexer().removeFromIndex(this, props[i]);
                }
            } else {
                log.warn("Auto indexing of properties is disabled, hence cannot delete properties of node '" + getPath() + "' from properties index.");
            }
        } catch(Exception e) {
            log.error(e, e);
        }
    }

    /**
     *
     */
    public int getType() throws RepositoryException {
        String maybeSplitPath = SplitPathConfig.splitPathIfRequired(path, getRepository().getSplitPathConfig());
        if (getRepository().getMap().isCollection(new Path(path))) {
            return NodeType.COLLECTION;
        } else if (getRepository().getMap().isResource(new Path(path))) {
            return NodeType.RESOURCE;
        } else if (getRepository().getMap().isResource(new Path(maybeSplitPath))) {
            return NodeType.RESOURCE;
        } else {
            return -1;
        }
        
    }

    /**
     * Get directory listing
     */
    public String getDirectoryListing(File file, String mimeType) {
        StringBuffer dirListing = new StringBuffer("<?xml version=\"1.0\"?>");
        if(mimeType.equals("application/xhtml+xml")) {
            dirListing.append("<html xmlns=\"http://www.w3.org/1999/xhtml\">");
            dirListing.append("<head>");
            dirListing.append("<title>"+path+"</title>");
            dirListing.append("</head>");
            dirListing.append("<body>");
            dirListing.append("<ul>");
            String[] children = file.list();
            for (int i = 0; i < children.length; i++) {
                File child = new File(file, children[i]);
                if (child.isFile()) {
                    dirListing.append("<li>File: <a href=\"" + children[i] + "\">" + children[i] + "</a></li>");
                } else if (child.isDirectory()) {
                    dirListing.append("<li>Directory: <a href=\"" + children[i] + "/\">" + children[i] + "/</a></li>");
                } else {
                    dirListing.append("<li>Child: <a href=\"" + children[i] + "\">" + children[i] + "</a></li>");
                }
            }
            dirListing.append("</ul>");
            dirListing.append("</body>");
            dirListing.append("</html>");
        } else if (mimeType.equals("application/xml")) {
            dirListing.append("<directory xmlns=\"http://www.wyona.org/yarep/1.0\" path=\""+path+"\" fs-path=\""+file+"\">");
            String[] children = file.list();
            for (int i = 0; i < children.length; i++) {
                File child = new File(file, children[i]);
                if (child.isFile()) {
                    dirListing.append("<file name=\"" + children[i] + "\"/>");
                } else if (child.isDirectory()) {
                    dirListing.append("<directory name=\"" + children[i] + "\"/>");
                } else {
                    dirListing.append("<child name=\"" + children[i] + "\"/>");
                }
            }
            dirListing.append("</directory>");
        } else {
            dirListing.append("<no-such-mime-type-supported>" + mimeType + "</no-such-mime-type-supported>");
        }
        return dirListing.toString();
    }

    /**
     * @see org.wyona.yarep.core.attributes.VersionableV1#getRevision(Date)
     */
    public Revision getRevision(Date date) throws Exception {
        log.debug("Use vfs-repo specific implementation: " + getPath());

        if (true) {
            log.debug("New implementation"); // According to tests with 15K revisions, the new implementation is about 80 times faster than the old one (8 millis instead 640 millis)
            DateIndexerSearcher dis = new DateIndexerSearcher(this, this.metaDir);
            if (dis.indexExists()) {
                Revision revision = dis.getRevision(date);
                if (revision != null) {
                    return revision;
                } else {
                    //log.warn("No revision found via data index, try to find otherwise ...");
                    log.warn("No revision found for node '" + path + "' and point in time '" + date + "'");
                    return null;
                }
            } else {
                log.warn("No date index yet, hence one will be created ...");
                dis.buildDateIndex();
                return getRevision(date);
            }
        } else {
            log.debug("Old implementation");
            if(log.isDebugEnabled()) log.debug("Use vfs-repo specific implementation ...");
            Revision[] revisions = getRevisions();
            for (int i = revisions.length - 1; i >= 0; i--) {
                //log.warn("DEBUG: Revison: " + revisions[i].getRevisionName());
                //Date creationDate = new Date(Long.parseLong(revisions[i].getRevisionName())); // INFO: The name of a revision is based on System.currentTimeMillis() (see createRevision(String))
                Date creationDate = revisions[i].getCreationDate(); // INFO: This method is slower than the above
                if (creationDate.before(date) || creationDate.equals(date)) {
                    if (log.isDebugEnabled()) log.debug("Revision found: " + revisions[i].getRevisionName());
                    log.debug("Number of revisions compared: " + (i + 1));
                    return revisions[i];
                }
            }
            log.warn("No revision found for node '" + path + "' and point in time '" + date + "'");
            return null;
        }
    }

    /**
     * @see org.wyona.yarep.core.attributes.VersionableV1#getTotalNumberOfRevisions()
     */
    public int getTotalNumberOfRevisions() throws Exception {
        log.warn("TODO: Not implemented yet!");
        return -1;
    }

    /**
     * @see org.wyona.yarep.core.attributes.VersionableV1#getRevisions(boolean)
     */
    public java.util.Iterator<Revision> getRevisions(boolean reverse) throws Exception {
        return new VFSRevisionIterator(this, this.metaDir, null, reverse);
    }

    /**
     * @see org.wyona.yarep.core.attributes.VersionableV1#getRevisions(Date, boolean)
     */
    public java.util.Iterator<Revision> getRevisions(Date date, boolean reverse) throws Exception {
        return new VFSRevisionIterator(this, this.metaDir, date, reverse);
    }

    /**
     * @see org.wyona.yarep.core.Node#getRevision(String)
     */
    public Revision getRevision(String revisionName) throws NoSuchRevisionException, RepositoryException {
        VirtualFileSystemRevision revision = new VirtualFileSystemRevision(this, revisionName);
        if (!revision.contentFile.exists()) {
            String logMessage = "Node '" + getPath() + "' has no such revision: " + revisionName;
            //log.error(logMessage);
            throw new NoSuchRevisionException(logMessage);
        }
        return revision;
    }

    public Revision getRevisionByTag(String tag) throws NoSuchRevisionException,
            RepositoryException {
        if (!areRevisionsRead) {
            readRevisions();
        }
        return super.getRevisionByTag(tag);
    }

    /**
     * @see org.wyona.yarep.core.Node#getRevisions()
     */
    public Revision[] getRevisions() throws RepositoryException {
        if (!areRevisionsRead) {
            readRevisions();
        }
        return super.getRevisions();
    }

    public boolean hasRevisionWithTag(String tag) throws RepositoryException {
        if (!areRevisionsRead) {
            readRevisions();
        }
        return super.hasRevisionWithTag(tag);
    }

    /**
     * Get revision content file
     * @param revisionName Name/ID of revision
     */
    public File getRevisionContentFile(String revisionName) {
        File revisionsBaseDir = new File(this.metaDir, REVISIONS_BASE_DIR);
        File revisionDir = new File(revisionsBaseDir, revisionName);
            
        return new File(revisionDir, VirtualFileSystemRevision.CONTENT_FILE_NAME);
    }

    /**
     * Get revision meta file
     */
    public File getRevisionMetaFile(String revisionName) {
        File revisionsBaseDir = new File(this.metaDir, REVISIONS_BASE_DIR);
        File revisionDir = new File(revisionsBaseDir, revisionName);
            
        return new File(revisionDir, META_FILE_NAME);
    }

    /**
     * Get meta file
     */
    public File getMetaFile() {
        return metaFile;
    }

    /**
     * Check for separators within a string. Because of backwards compatibility we cannot unescape the separator of existing data, because it might contain strings like "\:" but which have not been escaped like "\\:"
     */
    private String checkForSeparator(String st) throws RepositoryException {
        if (st.indexOf(PROPERTY_SEPARATOR) >= 0) {
            // INFO: Check node itself and its revisions!
            log.warn("Meta data string/property-value '" + st + "' contains reserved character '" + PROPERTY_SEPARATOR + "' (Node path: " + getPath() + ").");
        }
        return st;
    }

    /**
     * Escape separator within a string
     */
    private String escapeSeparator(String st) {
        if (st != null && st.indexOf(PROPERTY_SEPARATOR) >= 0) {
            log.debug("String '" + st + "' contains reserved character '" + PROPERTY_SEPARATOR + "' and hence will be escaped.");
            return st.replace("" + PROPERTY_SEPARATOR, "\\" + PROPERTY_SEPARATOR);
        } else {
            log.debug("String '" + st + "' contains no reserved character: " + PROPERTY_SEPARATOR);
            return st;
        }
    }

    /**
     * Unescape separator within a string
     */
    private String unescapeSeparator(String st) {
        return st.replace("\\" + PROPERTY_SEPARATOR, "" + PROPERTY_SEPARATOR);
    }

    /**
     * Escape linebreak within a string
     */
    private String escapeLinebreak(String st) {
        String lineSeparator = System.getProperty("line.separator");
        if (st != null && st.indexOf(lineSeparator) >= 0) {
            log.warn("String '" + st + "' contains a line break and hence the line break will be escaped.");
            return st.replace(lineSeparator, "\\ ");
        } else {
            log.debug("String '" + st + "' contains no line break.");
            return st;
        }
    }

    /**
     * Unescape linebreak within a string
     */
    private String unescapeLinebreak(String st) {
        return st.replace("\\ ", System.getProperty("line.separator"));
    }

    /**
     * Get index of where the value starts
     * @param line Line containing name and value separated by a SEPARATOR
     */
    private int getValueStartIndex(String line) throws RepositoryException {
        for (int i = 1; i < line.length(); i++) {
            if (line.charAt(i) == PROPERTY_SEPARATOR && !(line.charAt(i - 1) == '\\')) {
                return i + 1;
            }
        }
        String errMsg = "No name/value separator found within line '" + line + "' (Path: " + getPath() + ")!";
        throw new RepositoryException(errMsg);

        //return line.indexOf(">" + PROPERTY_SEPARATOR) + 2;
        //return line.indexOf(PROPERTY_SEPARATOR) + 1;
    }
}
