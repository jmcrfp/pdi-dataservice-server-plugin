package org.pentaho.di.repository.jcr;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Workspace;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.api.JackrabbitNodeTypeManager;
import org.apache.jackrabbit.rmi.repository.URLRemoteRepository;
import org.pentaho.di.cluster.ClusterSchema;
import org.pentaho.di.cluster.SlaveServer;
import org.pentaho.di.core.Condition;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.ProgressMonitorListener;
import org.pentaho.di.core.annotations.RepositoryPlugin;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleSecurityException;
import org.pentaho.di.core.row.ValueMetaAndData;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.partition.PartitionSchema;
import org.pentaho.di.repository.ObjectId;
import org.pentaho.di.repository.ObjectVersion;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.repository.RepositoryDirectory;
import org.pentaho.di.repository.RepositoryElementInterface;
import org.pentaho.di.repository.RepositoryLock;
import org.pentaho.di.repository.RepositoryMeta;
import org.pentaho.di.repository.RepositoryObject;
import org.pentaho.di.repository.RepositorySecurityProvider;
import org.pentaho.di.repository.StringObjectId;
import org.pentaho.di.repository.UserInfo;
import org.pentaho.di.repository.jcr.util.JCRObjectVersion;
import org.pentaho.di.shared.SharedObjects;
import org.pentaho.di.trans.TransMeta;
import org.w3c.dom.Document;


@RepositoryPlugin(
		id="JCRRepository", 
		i18nPackageName="org.pentaho.di.repository.jcr",
		description="JCRRepository.Description", 
		name="JCRRepository.Name", 
		metaClass="org.pentaho.di.repository.jcr.JCRRepositoryMeta",
		dialogClass="org.pentaho.di.ui.repository.jcr.JCRRepositoryDialog",
		versionBrowserClass="TODO" // TODO Implement a version browser class too...

		)
public class JCRRepository implements Repository {
	// private static Class<?> PKG = JCRRepository.class; // for i18n purposes, needed by Translator2!!   $NON-NLS-1$

	public static final String PDI_NODE_NAME = "pdi-root";
	
	public static final String NODE_TYPE_UNSTRUCTURED = "nt:unstructured";
	// public static final String NODE_TYPE_FOLDER = "nt:folder";
	public static final String NODE_TYPE_FILE = "nt:file";
	
	public static final String NODE_LOCK_NODE_NAME = "__PDI_LOCKS__";
	
	public static final String	MIX_REFERENCEABLE	= "mix:referenceable";
	public static final String	MIX_LOCKABLE	    = "mix:lockable";
	public static final String	MIX_VERSIONABLE	    = "mix:versionable";


	public static final String	EXT_TRANSFORMATION		= ".ktr";
	public static final String EXT_JOB					= ".kjb";
	public static final String	EXT_DATABASE			= ".kdb";
	public static final String	EXT_SLAVE_SERVER 		= ".ksl";
	public static final String	EXT_CLUSTER_SCHEMA 		= ".kcs";
	public static final String	EXT_PARTITION_SCHEMA	= ".kps";
	public static final String	EXT_STEP				= ".kst";
	public static final String	EXT_JOB_ENTRY			= ".kje";

	public static final String	PROPERTY_NAME       	= "Name";
	public static final String	PROPERTY_DESCRIPTION	= "Description";
	public static final String	PROPERTY_XML	= "XML";
	public static final String	PROPERTY_USER_CREATED = "UserCreated";
	public static final String	PROPERTY_VERSION_COMMENT = "VersionComment";

	public static final String	PROPERTY_PARENT_OBJECT = "ParentObject";
	public static final String	PROPERTY_CHILD_OBJECT = "ChildObject";

	private static final String	PROPERTY_LOCK_OBJECT = "LockObject";
	private static final String	PROPERTY_LOCK_LOGIN = "LockLogin";
	private static final String	PROPERTY_LOCK_USERNAME= "LockUsername";
	private static final String	PROPERTY_LOCK_MESSAGE = "LockMessage";
	private static final String	PROPERTY_LOCK_DATE = "LockDate";
	private static final String	PROPERTY_LOCK_PATH = "LockPath";
	
	public static final String PROPERTY_DELETED = "Deleted";
	
	public static final String PROPERTY_CODE_NR_SEPARATOR = "_#_";

	public static final String NS_PDI = "pdi";


	public static String NODE_TYPE_PDI_FOLDER = "pdifolder";
	private static String CND_TYPE_PDI_FOLDER= "["+NODE_TYPE_PDI_FOLDER+"] > nt:unstructured";

	public static String NODE_TYPE_RELATION = "pdirelation";
	private static String CND_TYPE_RELATION = "["+NODE_TYPE_RELATION+"] > nt:base"+Const.CR+"- "+PROPERTY_DESCRIPTION+" (STRING)"+Const.CR+"- "+PROPERTY_PARENT_OBJECT+" (REFERENCE)"+Const.CR+"- "+PROPERTY_CHILD_OBJECT+" (REFERENCE)";

	public static String NODE_TYPE_PDI_LOCK = "pdilock";
	private static String CND_TYPE_PDI_LOCK = "["+NODE_TYPE_PDI_LOCK+"] > nt:base"+Const.CR+"- "+PROPERTY_LOCK_OBJECT+" (REFERENCE)"+Const.CR+"- "+PROPERTY_LOCK_LOGIN+" (STRING)"+Const.CR+"- "+PROPERTY_LOCK_USERNAME+" (STRING)"+Const.CR+"- "+PROPERTY_LOCK_PATH+" (STRING)"+Const.CR+"- "+PROPERTY_LOCK_MESSAGE+" (STRING)"+Const.CR+"- "+PROPERTY_LOCK_DATE+" (DATE)";

	private JCRRepositoryMeta jcrRepositoryMeta;
	private UserInfo userInfo;
	private JCRRepositorySecurityProvider	securityProvider;
	
	private JCRRepositoryLocation	repositoryLocation;
	private URLRemoteRepository	jcrRepository;
	private Session	session;
	private Workspace	workspace;
	private JackrabbitNodeTypeManager nodeTypeManager;
	private Node	rootNode;

	private Node	lockNodeFolder;

	public JCRRepositoryTransDelegate transDelegate;
	private JCRRepositoryDatabaseDelegate	databaseDelegate;
	private JCRRepositoryPartitionDelegate	partitionDelegate;
	
	public JCRRepository() {
		this.transDelegate = new JCRRepositoryTransDelegate(this);
		this.databaseDelegate = new JCRRepositoryDatabaseDelegate(this);
		this.partitionDelegate = new JCRRepositoryPartitionDelegate(this);
	}
	
	public String getName() {
		return jcrRepositoryMeta.getName();
	}

	public String getVersion() {
		return jcrRepository.getDescriptor(javax.jcr.Repository.SPEC_VERSION_DESC);
	}

	public void init(RepositoryMeta repositoryMeta, UserInfo userInfo) {
		this.jcrRepositoryMeta = (JCRRepositoryMeta)repositoryMeta;
		this.userInfo = userInfo;
		this.repositoryLocation = jcrRepositoryMeta.getRepositoryLocation();		
		this.securityProvider = new JCRRepositorySecurityProvider(this, repositoryMeta, userInfo);
	}

	public void connect() throws KettleException, KettleSecurityException {
		try {
			jcrRepository = new URLRemoteRepository(repositoryLocation.getUrl());
			
			session = jcrRepository.login(new SimpleCredentials(userInfo.getUsername(), userInfo.getPassword()!=null ? userInfo.getPassword().toCharArray() : null ));
			workspace = session.getWorkspace();
			
			rootNode = session.getRootNode();
			
			nodeTypeManager = (JackrabbitNodeTypeManager) workspace.getNodeTypeManager();
			
			if (!nodeTypeManager.hasNodeType(NODE_TYPE_PDI_FOLDER)) {
				nodeTypeManager.registerNodeTypes(new ByteArrayInputStream(CND_TYPE_PDI_FOLDER.getBytes(Const.XML_ENCODING)), JackrabbitNodeTypeManager.TEXT_X_JCR_CND);
			}
			if (!nodeTypeManager.hasNodeType(NODE_TYPE_RELATION)) {
				nodeTypeManager.registerNodeTypes(new ByteArrayInputStream(CND_TYPE_RELATION.getBytes(Const.XML_ENCODING)), JackrabbitNodeTypeManager.TEXT_X_JCR_CND);
			}
			if (!nodeTypeManager.hasNodeType(NODE_TYPE_PDI_LOCK)) {
				nodeTypeManager.registerNodeTypes(new ByteArrayInputStream(CND_TYPE_PDI_LOCK.getBytes(Const.XML_ENCODING)), JackrabbitNodeTypeManager.TEXT_X_JCR_CND);
			}
			

			String[] prefixes = session.getNamespacePrefixes();
			if (Const.indexOfString(NS_PDI, prefixes)<0) {
				// TODO: create a pdi namespace...
				//
			}

			// Create a folder, type unstructured in the root to store the locks...
			//
			lockNodeFolder = null;
			NodeIterator nodes = rootNode.getNodes();
			while (nodes.hasNext() && lockNodeFolder==null) {
				Node node = nodes.nextNode();
				if (node.getName().equals(NODE_LOCK_NODE_NAME)) {
					lockNodeFolder = node;
				}
			}
			if (lockNodeFolder==null) {
				lockNodeFolder = rootNode.addNode(NODE_LOCK_NODE_NAME, "nt:unstructured");
			}
			
		} catch(Exception e) {
			session=null;
			throw new KettleException("Unable to connect to JCR repository on URL: "+repositoryLocation.getUrl(), e);
		}
	}
	
	public void disconnect() {
		session.logout();
		session=null;
	}

	public boolean isConnected() {
		return session!=null;
	}

	//
	// Directories
	//
	
	Node findFolderNode(RepositoryDirectory dir) throws KettleException {
		
		if (dir.isRoot()) {
			return rootNode; 
		}
		
		String path = dir.getPath();
		String relPath = path.substring(1);
		
		try {
			return rootNode.getNode(relPath);
		} catch(Exception e) {
			throw new KettleException("Unable to find folder node with path ["+dir.getPath()+"]", e);
		}
	}

	public RepositoryDirectory loadRepositoryDirectoryTree() throws KettleException {
		try {
			RepositoryDirectory root = new RepositoryDirectory();
			loadRepositoryDirectory(root, rootNode);
			root.setObjectId(null);
			return root;
		} catch (Exception e) {
			throw new KettleException("Unable to load directory tree from the JCR repository", e);
		}
	}
	
	private RepositoryDirectory loadRepositoryDirectory(RepositoryDirectory root, Node node) throws KettleException {
		try {
			NodeIterator nodes = node.getNodes();
			while (nodes.hasNext()) {
				Node subNode = nodes.nextNode();
				if (subNode.isNodeType(NODE_TYPE_PDI_FOLDER)) {
					RepositoryDirectory dir = new RepositoryDirectory();
					dir.setDirectoryName(subNode.getName());
					dir.setObjectId(new StringObjectId(subNode.getUUID()));
					root.addSubdirectory(dir);
					
					loadRepositoryDirectory(dir, subNode);
				}
			}
			
			return root;
		} catch (Exception e) {
			throw new KettleException("Unable to load directory structure from JCR repository", e);
		}
	}

	public void saveRepositoryDirectory(RepositoryDirectory dir) throws KettleException {
		try {
			Node parentNode = findFolderNode(dir.getParent());
			Node node = parentNode.addNode(dir.getDirectoryName(), NODE_TYPE_PDI_FOLDER);
			node.addMixin(MIX_REFERENCEABLE);
			node.addMixin(MIX_LOCKABLE);
			session.save();
			dir.setObjectId(new StringObjectId(node.getUUID()));
		} catch(Exception e) {
			throw new KettleException("Unable to save repository directory with path ["+dir.getPath()+"]", e);
		}
	}
	
	public RepositoryDirectory createRepositoryDirectory(RepositoryDirectory parent, String directoryPath) throws KettleException {
		try {
			String[] path = Const.splitPath(directoryPath, RepositoryDirectory.DIRECTORY_SEPARATOR);
			
			RepositoryDirectory follow = parent;
		    for (int level=0;level<path.length;level++)
		    {
		    	RepositoryDirectory child = follow.findChild(path[level]);
		    	if (child==null) {
		    		// create this one
		    		//
		    		child = new RepositoryDirectory(follow, path[level]);
		    		saveRepositoryDirectory(child);
		    	} 
		    	
		    	follow = child;
		    }
		    return follow;
		} catch(Exception e) {
			throw new KettleException("Unable to create directory with path ["+directoryPath+"]", e);
		}
	}
	
	public String[] getDirectoryNames(ObjectId id_directory) throws KettleException {
		return getObjectNames(id_directory, null);
	}


	
	
	
	
	
	
	private String calcDirectoryPath(RepositoryDirectory dir) {
		if (dir!=null) {
			return dir.getPath();
		} else {
			return "/";
		}
	}
	
	public String calcNodePath(RepositoryDirectory directory, String name, String extension) {
		StringBuilder path = new StringBuilder();
		
		String dirPath = calcDirectoryPath(directory);
		path.append(dirPath);
		if (!dirPath.endsWith("/")) {
			path.append("/");
		}
		
		path.append(name+extension);
		
		return path.toString();
	}
	
	public String calcRelativeNodePath(RepositoryDirectory directory, String name, String extension) {
		return calcNodePath(directory, name, extension).substring(1); // skip 1
	}

	public String calcNodePath(RepositoryElementInterface element) {
		RepositoryDirectory directory = element.getRepositoryDirectory();
		String name = element.getName();
		String extension = calcExtension(element);
		
		return calcNodePath(directory, name, extension);
	}
	
	String calcExtension(RepositoryElementInterface element) {
		if (TransMeta.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
			return EXT_TRANSFORMATION;
		} else
		if (JobMeta.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
			return EXT_JOB;
		} else
		if (DatabaseMeta.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
			return EXT_DATABASE;
		} else
		if (SlaveServer.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
			return EXT_SLAVE_SERVER;
		} else
		if (ClusterSchema.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
			return EXT_CLUSTER_SCHEMA;
		} else
		if (PartitionSchema.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
			return EXT_PARTITION_SCHEMA;
		} else {
			return ".xml";
		}
	}
	
	// General 
	//
	public void save(RepositoryElementInterface element, String versionComment, ProgressMonitorListener monitor) throws KettleException {
		try {
			
			if (TransMeta.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
				// Save transformation to repository...
				//
				transDelegate.saveTrans(element, versionComment, monitor);
			} else
			if (JobMeta.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
				// Save job to repository...
				//
			} else
			if (DatabaseMeta.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
				databaseDelegate.saveDatabaseMeta(element, versionComment, monitor);
			} else
			if (SlaveServer.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
				// TODO Save slave server to repository...
				//
				
			} else
			if (ClusterSchema.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
				// TODO Save cluster schema to repository...
				//
			} else
			if (PartitionSchema.REPOSITORY_ELEMENT_TYPE.equals(element.getRepositoryElementType())) {
				partitionDelegate.savePartitionSchema(element, versionComment, monitor);
			} else {
				throw new KettleException("It's not possible to save Class ["+element.getClass().getName()+"] to the JCR Repository");
			}
			
		} catch(Exception e) {
			throw new KettleException("Unable to save repository element ["+element+"]", e);
		}
	}
	
	
	public Node saveAsXML(String xml, RepositoryElementInterface element, String versionComment, ProgressMonitorListener monitor, boolean checkIn) throws KettleException {
		
		if (element instanceof DatabaseMeta) {
			 throw new KettleException("Please use save() or saveDatabaseMeta for databases");
		}
		
		try {
			
			Node node = createOrVersionNode(element, versionComment);
			ObjectId id = new StringObjectId(node.getUUID());
	
			node.setProperty(PROPERTY_XML, xml);
			
			session.save();

			if (checkIn) {
				Version version = node.checkin();
				element.setObjectVersion(new JCRObjectVersion(version, versionComment, userInfo.getLogin()));	
			}
			
			element.setObjectId(id);
					
			return node;
		}
		catch(Exception e) {
			throw new KettleException("Unable to save repository element ["+element+"] to the JCR repository as XML", e);
		}

	}

	public void unlockTransformation(ObjectId transformationId) throws KettleException {
		try {
			Node node = session.getNodeByUUID(transformationId.getId());
			unlockNode(node); 
		} catch(Exception e) {
			throw new KettleException("Unable to unlock transformation with id ["+transformationId+"]", e);
		}
	}

	public void lockTransformation(ObjectId transformationId, String message) throws KettleException {
		try {
			lockNode(transformationId, message);
		} catch(Exception e) {
			throw new KettleException("Unable to lock transformation with id ["+transformationId+"]", e);
		}
	}

	/**
	 * @param objectId
	 * @param isSessionScoped
	 * @param message
	 * @throws KettleException
	 */
	private void lockNode(ObjectId objectId, String message) throws KettleException {
		try {
			Node node = session.getNodeByUUID(objectId.getId());
			
			// There are a few bugs in the Jackrabbit locking implementation.
			// https://issues.apache.org/jira/browse/JCR-1634
			// It's marked as fixed in the next release, but that one's still in alpha release.
			//
			
			Node lockNode = lockNodeFolder.addNode(objectId.getId(), NODE_TYPE_PDI_LOCK);
			lockNode.setProperty(PROPERTY_LOCK_OBJECT, node);
			lockNode.setProperty(PROPERTY_LOCK_MESSAGE, message);
			lockNode.setProperty(PROPERTY_LOCK_DATE, Calendar.getInstance());
			lockNode.setProperty(PROPERTY_LOCK_LOGIN, userInfo.getLogin());
			lockNode.setProperty(PROPERTY_LOCK_USERNAME, userInfo.getUsername());
			lockNode.setProperty(PROPERTY_LOCK_PATH, node.getPath());
			
			session.save();
		} catch(Exception e) {
			throw new KettleException("Unable to lock node with id ["+objectId+"]", e);
		}
	}

	void unlockNode(Node node) throws KettleException {
		try {
			
			String uuid = node.getUUID();
			Node lockNode = lockNodeFolder.getNode(uuid);
			lockNode.remove();
			
			session.save();
		} catch(Exception e) {
			throw new KettleException("Unable to unlock node", e);
		}
	}

	public boolean exists(RepositoryElementInterface repositoryElement) throws KettleException {
		try {
			Node node = getNode(repositoryElement.getName(), repositoryElement.getRepositoryDirectory(), calcExtension(repositoryElement));
			if (node==null) return false;
			
			if (getPropertyBoolean(node, PROPERTY_DELETED, false)) {
				return false;
			}
			
			return true;
		} catch(Exception e) {
			throw new KettleException("Unable to verify if the repository element ["+repositoryElement.getName()+"] exists", e);
		}
	}

	
	public TransMeta loadTransformation(String transname, RepositoryDirectory repdir, ProgressMonitorListener monitor, boolean setInternalVariables, String versionLabel) throws KettleException {
		return transDelegate.loadTransformation(transname, repdir, monitor, setInternalVariables, versionLabel);
	}
	
	public SharedObjects readTransSharedObjects(TransMeta transMeta) throws KettleException {
		return transDelegate.readTransSharedObjects(transMeta);
	}

	
	
	public Version getLastVersion(Node node) throws UnsupportedRepositoryOperationException, RepositoryException {
		
		VersionHistory versionHistory = node.getVersionHistory();
		Version version = versionHistory.getRootVersion();
		
		Version[] successors = version.getSuccessors();
		
		while (successors!=null && successors.length>0) {
			version = successors[0];
			successors = version.getSuccessors();
		}
		return version;
	}
	
	Node getVersionNode(Version version) throws PathNotFoundException, RepositoryException {
		return version.getNode(JcrConstants.JCR_FROZENNODE);
	}
	
	
	
	
	
	
	
	
	
	

	public void deleteJob(ObjectId jobId) throws KettleException {
		//	TODO
		
	}
	
	public void removeVersion(ObjectId id, String version) throws KettleException {
		try {
			Node node = session.getNodeByUUID(id.getId());
			VersionHistory versionHistory = node.getVersionHistory();
			versionHistory.removeVersion(version);
		} catch(Exception e) {
			throw new KettleException("Unable to remove last version of object with ID ["+id+"]", e);
		}
	}

	public void deleteTransformation(ObjectId transformationId) throws KettleException {
		transDelegate.deleteTransformation(transformationId);
	}

	public void deleteClusterSchema(ObjectId id_cluster) throws KettleException {
	}

	public void deleteCondition(ObjectId id_condition) throws KettleException {
	}

	public void deletePartitionSchema(ObjectId partitionSchemaId) throws KettleException {
	}

	public void deleteRepositoryDirectory(RepositoryDirectory dir) throws KettleException {
		try {
			Node dirNode = findFolderNode(dir);
			dirNode.remove();
			session.save();
		} catch(Exception e) {
			throw new KettleException("Unable to delete directory with path ["+dir.getPath()+"]", e);
		}
	}

	public void deleteSlave(ObjectId id_slave) throws KettleException {
	}

	public void deleteDatabaseMeta(String databaseName) throws KettleException {
	}

	public ObjectId getClusterID(String name) throws KettleException {
		return getObjectId(name, null, EXT_SLAVE_SERVER);
	}

	public ObjectId[] getClusterIDs(boolean includeDeleted) throws KettleException {
		return getObjectIDs((ObjectId)null, EXT_CLUSTER_SCHEMA, includeDeleted);
	}

	public String[] getClusterNames() throws KettleException {
		return getObjectNames(null, EXT_CLUSTER_SCHEMA);
	}

	public ObjectId[] getClusterSlaveIDs(ObjectId clusterSchemaId) throws KettleException {
		return null;
	}

	public String[] getClustersUsingSlave(ObjectId id_slave) throws KettleException {
		return null;
	}

	public ObjectId[] getDatabaseAttributeIDs(ObjectId databaseId) throws KettleException {
		return null;
	}

	public ObjectId getDatabaseID(String name) throws KettleException {
		return getObjectId(name, null, EXT_DATABASE);
	}

	public ObjectId[] getDatabaseIDs(boolean includeDeleted) throws KettleException {
		return getObjectIDs((ObjectId)null, EXT_DATABASE, includeDeleted);
	}

	public String[] getDatabaseNames() throws KettleException {
		return getObjectNames(null, EXT_DATABASE);
	}

	public ObjectId getJobId(String name, RepositoryDirectory repositoryDirectory) throws KettleException {
		return getObjectId(name, repositoryDirectory, EXT_TRANSFORMATION);
	}

	public RepositoryLock getJobLock(ObjectId jobId) throws KettleException {
		return getLock(jobId);
	}

	public String[] getJobNames(ObjectId id_directory) throws KettleException {
		return getObjectNames(id_directory, EXT_JOB);
	}

	public ObjectId[] getJobNoteIDs(ObjectId jobId) throws KettleException {
		return null;
	}

	public List<RepositoryObject> getJobObjects(ObjectId id_directory, boolean includeDeleted) throws KettleException {
		return getPdiObjects(id_directory, EXT_JOB, RepositoryObject.STRING_OBJECT_TYPE_JOB, includeDeleted);
	}

	public String[] getJobsUsingDatabase(ObjectId databaseId) throws KettleException {
		return null;
	}

	public ObjectId getPartitionSchemaID(String name) throws KettleException {
		return getObjectId(name, null, EXT_PARTITION_SCHEMA);
	}

	public ObjectId[] getPartitionSchemaIDs(boolean includeDeleted) throws KettleException {
		return getObjectIDs((ObjectId)null, EXT_PARTITION_SCHEMA, includeDeleted);
	}

	public String[] getPartitionSchemaNames() throws KettleException {
		return getObjectNames(null, EXT_PARTITION_SCHEMA);
	}

	public RepositoryMeta getRepositoryMeta() {
		return jcrRepositoryMeta;
	}

	public RepositorySecurityProvider getSecurityProvider() {
		return securityProvider;
	}

	public ObjectId getSlaveID(String name) throws KettleException {
		return getObjectId(name, null, EXT_SLAVE_SERVER);
	}

	public ObjectId[] getSlaveIDs(boolean includeDeleted) throws KettleException {
		return getObjectIDs((ObjectId)null, EXT_SLAVE_SERVER, includeDeleted);
	}

	public String[] getSlaveNames() throws KettleException {
		return getObjectNames(null, EXT_SLAVE_SERVER);
	}

	public List<SlaveServer> getSlaveServers() throws KettleException {
		return null;
	}


	public ObjectId[] getSubConditionIDs(ObjectId id_condition) throws KettleException {
		return null;
	}

	public ObjectId[] getTransNoteIDs(ObjectId transformationId) throws KettleException {
		return null;
	}

	public ObjectId[] getTransformationClusterSchemaIDs(ObjectId transformationId) throws KettleException {
		return null;
	}

	public ObjectId[] getTransformationConditionIDs(ObjectId transformationId) throws KettleException {
		return null;
	}

	public ObjectId[] getTransformationDatabaseIDs(ObjectId transformationId) throws KettleException {
		return null;
	}
	
	/**
	 * Find the object ID of an object.
	 * 
	 * @param name
	 * @param repositoryDirectory
	 * @param extension
	 * 
	 * @return the object ID (UUID) or null if the node couldn't be found.
	 * 
	 * @throws KettleException In case something went horribly wrong
	 */
	public ObjectId getObjectId(String name, RepositoryDirectory repositoryDirectory, String extension) throws KettleException {
		try {
			Node node = getNode(name, repositoryDirectory, extension);
			if (node==null) return null;
			return new StringObjectId(node.getUUID());
		} catch(Exception e) {
			throw new KettleException("Unable to get ID for object ["+name+"] + in directory ["+repositoryDirectory+"] with extension ["+extension+"]", e);
		}
	}
	
	public Node getNode(String name, RepositoryDirectory repositoryDirectory, String extension) throws KettleException {
		String path = calcRelativeNodePath(repositoryDirectory, name, extension);
		try {
			return rootNode.getNode(path);
		} catch(PathNotFoundException e) {
			return null; // Not found!
		} catch(Exception e) {
			throw new KettleException("Unable to get node for object ["+path+"]", e);
		}
		
	}

	public ObjectId[] getObjectIDs(RepositoryDirectory repositoryDirectory, String extension) throws KettleException {

		String path = repositoryDirectory.getPath();
		try {

			Node folderNode;
			if (path.length()<=1) {
				folderNode = rootNode;
			} else {
				folderNode = rootNode.getNode(path.substring(1));
			}
			
			List<ObjectId> list = new ArrayList<ObjectId>();
			NodeIterator nodeIterator = folderNode.getNodes();
			
			while (nodeIterator.hasNext()) {
				Node node = nodeIterator.nextNode();
				if (Const.isEmpty(extension)) {
					if (node.isNodeType(NODE_TYPE_PDI_FOLDER)) {
						list.add(new StringObjectId(node.getUUID()));
					}
				} else {
					if (node.isNodeType(NODE_TYPE_UNSTRUCTURED)) {
						if (node.getName().endsWith(extension)) {
							list.add(new StringObjectId(node.getUUID()));
						}
					}
				}
			}
			return list.toArray(new ObjectId[list.size()]);
		} catch(Exception e) {
			throw new KettleException("Unable to get ID for object ["+path+"]", e);
		}
	}
	
	public ObjectId[] getObjectIDs(ObjectId id_directory, String extension, boolean includeDeleted) throws KettleException {

		try {

			Node folderNode;
			if (id_directory==null) {
				folderNode = rootNode;
			} else {
				folderNode = session.getNodeByUUID(id_directory.getId());
			}

			return getObjectIDs(folderNode, extension, includeDeleted);
		} catch(Exception e) {
			throw new KettleException("Unable to get ID for object ["+id_directory+"]", e);
		}
	}

	private ObjectId[] getObjectIDs(Node folderNode, String extension, boolean includeDeleted) throws KettleException {
		try {
			List<ObjectId> list = new ArrayList<ObjectId>();
			NodeIterator nodeIterator = folderNode.getNodes();
			
			while (nodeIterator.hasNext()) {
				Node node = nodeIterator.nextNode();
				if (Const.isEmpty(extension)) {
					if (node.isNodeType(NODE_TYPE_PDI_FOLDER)) {
						list.add(new StringObjectId(node.getUUID()));
					}
				} else {
					if (node.isNodeType(NODE_TYPE_UNSTRUCTURED)) {
						if (node.getName().endsWith(extension)) {
							
							// See if the node is deleted or not.
							//
							if (includeDeleted || !getPropertyBoolean(node, PROPERTY_DELETED, false)) {
								list.add(new StringObjectId(node.getUUID()));
							}
						}
					}
				}
			}
			return list.toArray(new ObjectId[list.size()]);
		}
		catch(Exception e) {
			throw new KettleException("Unable to get object IDs from folder node ["+folderNode+getName()+"]", e);
		}
	}

	public ObjectId getTransformationID(String name, RepositoryDirectory repositoryDirectory) throws KettleException {
		return getObjectId(name, repositoryDirectory, EXT_TRANSFORMATION);
	}

	public RepositoryLock getTransformationLock(ObjectId transformationId) throws KettleException {
		return getLock(transformationId);
	}
	
	RepositoryLock getLock(ObjectId objectId) throws KettleException {
		try {
			
			Node lockNode = lockNodeFolder.getNode(objectId.getId());
			String message = lockNode.getProperty(PROPERTY_LOCK_MESSAGE).getString();
			String login = lockNode.getProperty(PROPERTY_LOCK_LOGIN).getString();
			String username = lockNode.getProperty(PROPERTY_LOCK_USERNAME).getString();
			String path = lockNode.getProperty(PROPERTY_LOCK_PATH).getString();
			Date lockDate = lockNode.getProperty(PROPERTY_LOCK_DATE).getDate().getTime();
			Node parent = lockNode.getProperty(PROPERTY_LOCK_OBJECT).getNode();
			
			// verify node path with lock
			//
			if (path!=null && !path.equals(parent.getPath())) {
				throw new KettleException("Problem found in locking system, referenced node path ["+parent.getPath()+"] is not the same as the stored path ["+path+"]");
			}
			
			return new RepositoryLock(objectId, message, login, username, lockDate); 
		} catch(PathNotFoundException e) {
			return null; // NOT FOUND!
		}
		catch(Exception e) {
			throw new KettleException("Unable to get lock status for object ["+objectId+"]", e);
		}
	}

	private String[] getObjectNames(ObjectId id_directory, String extension) throws KettleException {
		try {
			Node folderNode;
			if (id_directory==null) {
				folderNode = rootNode;
			} else {
				folderNode = session.getNodeByUUID(id_directory.getId());
			}
			List<String> names = new ArrayList<String>();
			NodeIterator nodes = folderNode.getNodes();
			while (nodes.hasNext()) {
				Node node = nodes.nextNode();
				
				if (Const.isEmpty(extension)) {
					// Folders
					//
					if (node.isNodeType(NODE_TYPE_PDI_FOLDER)) {
						names.add(node.getName());
					}
				} else {
					// Normal Objects
					//
					if (node.isNodeType(NODE_TYPE_UNSTRUCTURED)) {
						String fullname = node.getName();
						if (fullname.endsWith(extension)) {
							names.add(fullname.substring(0, fullname.length() - extension.length()));
						}
					}
				}
				
			}
			
			return names.toArray(new String[names.size()]);
		}catch(Exception e) {
			throw new KettleException("Unable to get list of object names from directory ["+id_directory+"]", e);
		}
	}

	public String[] getTransformationNames(ObjectId id_directory) throws KettleException {
		return getObjectNames(id_directory, EXT_TRANSFORMATION);
	}

	public List<RepositoryObject> getTransformationObjects(ObjectId id_directory, boolean includeDeleted) throws KettleException {
		return getPdiObjects(id_directory, EXT_TRANSFORMATION, RepositoryObject.STRING_OBJECT_TYPE_TRANSFORMATION, includeDeleted);
	}

	private List<RepositoryObject> getPdiObjects(ObjectId id_directory, String extension, String objectType, boolean includeDeleted) throws KettleException {
		
		List<RepositoryObject> list = new ArrayList<RepositoryObject>();
		try {
			ObjectId[] ids = getObjectIDs(id_directory, extension, includeDeleted);
			for (ObjectId objectId : ids) {
				Node transNode = session.getNodeByUUID(objectId.getId());
				Version version = getLastVersion(transNode);

				String name = transNode.getName();
				String description = transNode.getProperty(PROPERTY_DESCRIPTION).getString() + " - v"+version.getName();
				String userCreated = transNode.getProperty(PROPERTY_USER_CREATED).getString();
				Date dateCreated = version.getCreated().getTime();
				String lockMessage = transNode.getProperty(PROPERTY_VERSION_COMMENT).getString();
				
				list.add(new RepositoryObject(name.substring(0, name.length()-extension.length()), userCreated, dateCreated, objectType, description, lockMessage)); // TODO : add the lock message
				
			}
			return list;
		}
		catch(Exception e) {
			throw new KettleException("Unable to get list of transformations from directory ["+id_directory+"]", e);
		}
	}

	public ObjectId[] getTransformationPartitionSchemaIDs(ObjectId transformationId) throws KettleException {
		return null;
	}

	public String[] getTransformationsUsingCluster(ObjectId id_cluster) throws KettleException {
		return null;
	}

	public String[] getTransformationsUsingDatabase(ObjectId databaseId) throws KettleException {
		return null;
	}

	public String[] getTransformationsUsingPartitionSchema(ObjectId partitionSchemaId) throws KettleException {
		return null;
	}

	public String[] getTransformationsUsingSlave(ObjectId id_slave) throws KettleException {
		return null;
	}

	public UserInfo getUserInfo() {
		return userInfo;
	}

	public ObjectId insertClusterSlave(ClusterSchema clusterSchema, SlaveServer slaveServer) throws KettleException {
		return null;
	}

	public void insertJobEntryDatabase(ObjectId jobId, ObjectId jobEntryId, ObjectId databaseId) throws KettleException {
	}

	public void insertJobNote(ObjectId jobId, ObjectId id_note) throws KettleException {
	}

	public ObjectId insertLogEntry(String description) throws KettleException {
		return null;
	}

	// Simply keep the relationship between transformation and database in this case...
	// We're not that interested in the step-database relationship.
	//
	public void insertStepDatabase(ObjectId transformationId, ObjectId stepId, ObjectId databaseId) throws KettleException {
		/* TODO Find a good implementation for these N:N relationships
		 * 
		 * Perhaps we simply have to store all the used transformation nodes in the Value[].
		 * However, this might cause some mgt issues since we then have to figure out the transformations and jobs in the array as one gets deleted etc.
		 * 
		try {
			Node transNode = session.getNodeByUUID(transformationId.getId());
			Node dbNode = session.getNodeByUUID(databaseId.getId());
			
			// So we want to create a new node with type NODE_TYPE_REL_TRANS_DB
			//
			rootNode
			
			Value value = session.getValueFactory().createValue(document); //creates the reference value
		} catch (Exception e) {
			throw new KettleException("Unable to add reference between transformation ["+transformationId+"] and database ["+databaseId+"]", e);
		}
		*/
	}

	public void insertTransNote(ObjectId transformationId, ObjectId id_note) throws KettleException {
	}

	public void insertTransStepCondition(ObjectId transformationId, ObjectId stepId, ObjectId id_condition) throws KettleException {
	}

	public ObjectId insertTransformationCluster(ObjectId transformationId, ObjectId id_cluster) throws KettleException {
		return null;
	}

	public ObjectId insertTransformationPartitionSchema(ObjectId transformationId, ObjectId partitionSchemaId) throws KettleException {
		return null;
	}

	public ObjectId insertTransformationSlave(ObjectId transformationId, ObjectId id_slave) throws KettleException {
		return null;
	}

	public ClusterSchema loadClusterSchema(ObjectId clusterSchemaId, List<SlaveServer> slaveServers, String versionLabel) throws KettleException {
		try {
			Version version = getVersion(session.getNodeByUUID(clusterSchemaId.getId()), null); // TODO
			Node node = getVersionNode(version);
			
			Document doc = XMLHandler.loadXMLString(  node.getProperty(PROPERTY_XML).getString() );
			
			ClusterSchema clusterSchema = new ClusterSchema( XMLHandler.getSubNode(doc, ClusterSchema.XML_TAG), slaveServers );
			
			// Grab the Version comment...
			//
			String versionComment = node.getProperty(PROPERTY_VERSION_COMMENT).getString();
			String userCreated = node.getProperty(PROPERTY_USER_CREATED).getString();
			
			clusterSchema.setObjectVersion(new JCRObjectVersion(version, versionComment, userCreated));
			clusterSchema.clearChanged();			
			
			return clusterSchema;
		}
		catch(Exception e) {
			throw new KettleException("Unable to load cluster schema from object ["+clusterSchemaId+"]", e);
		}
	}

	public Condition loadCondition(ObjectId id_condition) throws KettleException {
		return null;
	}

	public Condition loadConditionFromStepAttribute(ObjectId stepId, String code) throws KettleException {
		return null;
	}

	public DatabaseMeta loadDatabaseMeta(ObjectId databaseId, String versionLabel) throws KettleException {
		return databaseDelegate.loadDatabaseMeta(databaseId, versionLabel);
	}

	public JobMeta loadJob(String jobname, RepositoryDirectory repdir, ProgressMonitorListener monitor, String versionLabel) throws KettleException {
		return null;
	}

	public PartitionSchema loadPartitionSchema(ObjectId partitionSchemaId, String versionLabel) throws KettleException {
		try {
			Version version = getVersion(session.getNodeByUUID(partitionSchemaId.getId()), null);
			Node node = getVersionNode(version);
			
			Document doc = XMLHandler.loadXMLString(node.getProperty(PROPERTY_XML).getString());
			
			PartitionSchema partitionSchema = new PartitionSchema( XMLHandler.getSubNode(doc, PartitionSchema.XML_TAG) );
			
			// Grab the Version comment...
			//
			String versionComment = node.getProperty(PROPERTY_VERSION_COMMENT).getString();
			String userCreated = node.getProperty(PROPERTY_USER_CREATED).getString();

			partitionSchema.setObjectId(partitionSchemaId);
			partitionSchema.setObjectVersion(new JCRObjectVersion(version, versionComment, userCreated));
			partitionSchema.clearChanged();			
			
			return partitionSchema;
		}
		catch(Exception e) {
			throw new KettleException("Unable to load database from object ["+partitionSchemaId+"]", e);
		}
	}
	
	public SlaveServer loadSlaveServer(ObjectId id_slave_server, String versionLabel) throws KettleException {
		return null;
	}

	public ValueMetaAndData loadValueMetaAndData(ObjectId id_value) throws KettleException {
		return null;
	}

	public void lockJob(ObjectId jobId, String message) throws KettleException {
		try {
			lockNode(jobId, message);
		} catch(Exception e) {
			throw new KettleException("Unable to lock job with id ["+jobId+"]", e);
		}
	}


	public List<DatabaseMeta> readDatabases() throws KettleException {
		ObjectId[] ids = getDatabaseIDs(false);
		List<DatabaseMeta> list = new ArrayList<DatabaseMeta>();
		for (ObjectId objectId : ids) {
			list.add(loadDatabaseMeta(objectId, null)); // Load the last version
		}
		return list;
	}

	public SharedObjects readJobMetaSharedObjects(JobMeta jobMeta) throws KettleException {
		return null;
	}

	public ObjectId renameDatabase(ObjectId databaseId, String newname) throws KettleException {
		return databaseDelegate.renameDatabase(databaseId, newname);
	}

	public ObjectId renameJob(ObjectId jobId, RepositoryDirectory newDirectory, String newName) throws KettleException {
		return null;
	}

	public ObjectId renameRepositoryDirectory(RepositoryDirectory dir) throws KettleException {
		return null;
	}

	public ObjectId renameTransformation(ObjectId transformationId, RepositoryDirectory newDirectory, String newName) throws KettleException {
		return null;
	}

	public ObjectId saveCondition(Condition condition) throws KettleException {
		return null;
	}

	public ObjectId saveCondition(Condition condition, ObjectId id_condition_parent) throws KettleException {
		return null;
	}

	public void saveConditionStepAttribute(ObjectId transformationId, ObjectId stepId, String code, Condition condition) throws KettleException {
	}

	// Save/Load database from step/jobentry attribute
	
	
	public void saveDatabaseMetaJobEntryAttribute(ObjectId jobId, ObjectId jobEntryId, String code, DatabaseMeta database) throws KettleException {
		try {
			if (database!=null && database.getObjectId()!=null) {
				Node node = session.getNodeByUUID(database.getObjectId().getId());
				saveStepAttribute(jobId, jobEntryId, code, node);
			}
		} catch(Exception e) {
			throw new KettleException("Unable to save database reference as a job entry attribute for job entry id ["+jobEntryId+"] and code ["+code+"]", e);
		}
	}

	public void saveDatabaseMetaStepAttribute(ObjectId transformationId, ObjectId stepId, String code, DatabaseMeta database) throws KettleException {
		try {
			if (database!=null && database.getObjectId()!=null) {
				Node node = session.getNodeByUUID(database.getObjectId().getId());
				saveStepAttribute(transformationId, stepId, code, node);
			}
		} catch(Exception e) {
			throw new KettleException("Unable to save database reference as a step attribute for step id ["+stepId+"] and code ["+code+"]", e);
		}
	}

	public DatabaseMeta loadDatabaseMetaFromJobEntryAttribute(ObjectId jobEntryId, String code, List<DatabaseMeta> databases) throws KettleException {
		try {
			Node node = getJobEntryAttributeNode(jobEntryId, code);
			ObjectId databaseId = new StringObjectId(node.getUUID());
			
			return DatabaseMeta.findDatabase(databases, databaseId);
		} catch(Exception e) {
			throw new KettleException("Unable to load database reference from a job entry attribute for job entry id ["+jobEntryId+"] and code ["+code+"]", e);
		}
	}
	
	public DatabaseMeta loadDatabaseMetaFromStepAttribute(ObjectId stepId, String code, List<DatabaseMeta> databases) throws KettleException {
		try {
			Node node = getStepAttributeNode(stepId, code);
			ObjectId databaseId = new StringObjectId(node.getUUID());
			
			return DatabaseMeta.findDatabase(databases, databaseId);
		} catch(Exception e) {
			throw new KettleException("Unable to save database reference as a step attribute for step id ["+stepId+"] and code ["+code+"]", e);
		}
	}


	
	// JOB ENTRY ATTRIBUTES

	/**
	 * Special edition for JCR nodes, creates a reference to a node
	 */
	public void saveJobEntryAttribute(ObjectId jobId, ObjectId jobEntryId, String code, Node node) throws KettleException {
		try {
			session.getNodeByUUID(jobEntryId.getId()).setProperty(code, node);
		} catch(Exception e) {
			throw new KettleException("Error saving job entry node reference attribute ["+code+"] for job entry with id ["+jobEntryId+"]", e);
		}
	}

	public void saveJobEntryAttribute(ObjectId jobId, ObjectId jobEntryId, String code, String value) throws KettleException {
		try {
			session.getNodeByUUID(jobEntryId.getId()).setProperty(code, value);
		} catch(Exception e) {
			throw new KettleException("Error saving job entry attribute ["+code+"] for job entry with id ["+jobEntryId+"]", e);
		}
	}

	public void saveJobEntryAttribute(ObjectId jobId, ObjectId jobEntryId, String code, boolean value) throws KettleException {
		try {
			session.getNodeByUUID(jobEntryId.getId()).setProperty(code, value);
		} catch(Exception e) {
			throw new KettleException("Error saving job entry attribute ["+code+"] for job entry with id ["+jobEntryId+"]", e);
		}
	}

	public void saveJobEntryAttribute(ObjectId jobId, ObjectId jobEntryId, String code, long value) throws KettleException {
		try {
			session.getNodeByUUID(jobEntryId.getId()).setProperty(code, value);
		} catch(Exception e) {
			throw new KettleException("Error saving job entry attribute ["+code+"] for job entry with id ["+jobEntryId+"]", e);
		}
	}

	public void saveJobEntryAttribute(ObjectId jobId, ObjectId jobEntryId, int nr, String code, String value) throws KettleException {
		try {
			session.getNodeByUUID(jobEntryId.getId()).setProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr, value);
		} catch(Exception e) {
			throw new KettleException("Error saving job entry attribute ["+code+"] for job entry with id ["+jobEntryId+"]", e);
		}
	}

	public void saveJobEntryAttribute(ObjectId jobId, ObjectId jobEntryId, int nr, String code, boolean value) throws KettleException {
		try {
			session.getNodeByUUID(jobEntryId.getId()).setProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr, value);
		} catch(Exception e) {
			throw new KettleException("Error saving job entry attribute ["+code+"] for job entry with id ["+jobEntryId+"]", e);
		}
	}

	public void saveJobEntryAttribute(ObjectId jobId, ObjectId jobEntryId, int nr, String code, long value) throws KettleException {
		try {
			session.getNodeByUUID(jobEntryId.getId()).setProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr, value);
		} catch(Exception e) {
			throw new KettleException("Error saving job entry attribute ["+code+"] for job entry with id ["+jobEntryId+"]", e);
		}
	}
	
	public int countNrJobEntryAttributes(ObjectId jobEntryId, String code) throws KettleException {
		try {
			Node jobEntryNode = session.getNodeByUUID(jobEntryId.getId());
			PropertyIterator properties = jobEntryNode.getProperties();
			int nr = 0;
			while (properties.hasNext()) {
				Property property = properties.nextProperty();
				if (property.getName().equals(code) || property.getName().startsWith(code+PROPERTY_CODE_NR_SEPARATOR)) {
					nr++;
				}
			}
			return nr; 
		} catch (RepositoryException e) {
			throw new KettleException("Unable to count the nr of job entry attributes for job entry with ID ["+jobEntryId+"] and code ["+code+"]", e);
		}
	}
	
	public boolean getJobEntryAttributeBoolean(ObjectId jobEntryId, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(jobEntryId.getId()).getProperty(code).getBoolean();
		} catch(PathNotFoundException e) {
			return false;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get job entry attribute for entry with id ["+jobEntryId+"] and code ["+code+"]", e);
		}
	}
	
	public boolean getJobEntryAttributeBoolean(ObjectId jobEntryId, int nr, String code) throws KettleException {
		try {
			return session.getNodeByUUID(jobEntryId.getId()).getProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr).getBoolean();
		} catch(PathNotFoundException e) {
			return false;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get job entry attribute for entry with id ["+jobEntryId+"] and code ["+code+"], nr="+nr, e);
		}
	}
	
	public boolean getJobEntryAttributeBoolean(ObjectId jobEntryId, String code, boolean def) throws KettleException {
		try {
			return session.getNodeByUUID(jobEntryId.getId()).getProperty(code).getBoolean();
		} catch(PathNotFoundException e) {
			return def;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get step attribute for step with id ["+jobEntryId+"] and code ["+code+"]", e);
		}
	}
	
	public long getJobEntryAttributeInteger(ObjectId jobEntryId, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(jobEntryId.getId()).getProperty(code).getLong();
		} catch(PathNotFoundException e) {
			return 0L;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get job entry attribute for entry with id ["+jobEntryId+"] and code ["+code+"]", e);
		}
	}

	public long getJobEntryAttributeInteger(ObjectId jobEntryId, int nr, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(jobEntryId.getId()).getProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr).getLong();
		} catch(PathNotFoundException e) {
			return 0L;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get job entry attribute for entry with id ["+jobEntryId+"] and code ["+code+"], nr="+nr, e);
		}

	}
	
	public String getJobEntryAttributeString(ObjectId jobEntryId, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(jobEntryId.getId()).getProperty(code).getString();
		} catch(PathNotFoundException e) {
			return null;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get job entry attribute for entry with id ["+jobEntryId+"] and code ["+code+"]", e);
		}
	}
	
	public String getJobEntryAttributeString(ObjectId jobEntryId, int nr, String code) throws KettleException {
		try {
			return session.getNodeByUUID(jobEntryId.getId()).getProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr).getString();
		} catch(PathNotFoundException e) {
			return null;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get job entry attribute for entry with id ["+jobEntryId+"] and code ["+code+"], nr="+nr, e);
		}
	}

	public Node getJobEntryAttributeNode(ObjectId jobEntryId, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(jobEntryId.getId()).getProperty(code).getNode();
		} catch(PathNotFoundException e) {
			return null;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get job entry attribute for entry with id ["+jobEntryId+"] and code ["+code+"]", e);
		}
	}

	// STEP ATTRIBUTES
	



	public int countNrStepAttributes(ObjectId stepId, String code) throws KettleException { 
		try {
			Node stepNode = session.getNodeByUUID(stepId.getId());
			PropertyIterator properties = stepNode.getProperties();
			int nr = 0;
			while (properties.hasNext()) {
				Property property = properties.nextProperty();
				if (property.getName().equals(code) || property.getName().startsWith(code+PROPERTY_CODE_NR_SEPARATOR)) {
					nr++;
				}
			}
			return nr; 
		} catch (RepositoryException e) {
			throw new KettleException("Unable to count the nr of step attributes for step with ID ["+stepId+"] and code ["+code+"]", e);
		}
	}

	/**
	 * We can ignore this one, it's only used for backward compatibility in the older KettleDatabaseRepository.
	 */
	public ObjectId findStepAttributeID(ObjectId stepId, int nr, String code) throws KettleException { 
		return null; 
	}

	public void saveStepAttribute(ObjectId transformationId, ObjectId stepId, String code, String value) throws KettleException {
		try {
			session.getNodeByUUID(stepId.getId()).setProperty(code, value);
		} catch(Exception e) {
			throw new KettleException("Error saving step attribute ["+code+"] for step with id ["+stepId+"]", e);
		}
	}

	/** Specially for JCR, creates a reference to a node
	 * 
	 * @param transformationId
	 * @param stepId
	 * @param code
	 * @param node The node to reference
	 * @throws KettleException
	 */
	public void saveStepAttribute(ObjectId transformationId, ObjectId stepId, String code, Node node) throws KettleException {
		try {
			session.getNodeByUUID(stepId.getId()).setProperty(code, node);
		} catch(Exception e) {
			throw new KettleException("Error saving step node reference attribute ["+code+"] for step with id ["+stepId+"]", e);
		}
	}

	public void saveStepAttribute(ObjectId transformationId, ObjectId stepId, String code, boolean value) throws KettleException {
		try {
			session.getNodeByUUID(stepId.getId()).setProperty(code, value);
		} catch(Exception e) {
			throw new KettleException("Error saving step attribute ["+code+"] for step with id ["+stepId+"]", e);
		}
	}

	public void saveStepAttribute(ObjectId transformationId, ObjectId stepId, String code, long value) throws KettleException {
		try {
			session.getNodeByUUID(stepId.getId()).setProperty(code, value);
		} catch(Exception e) {
			throw new KettleException("Error saving step attribute ["+code+"] for step with id ["+stepId+"]", e);
		}
	}

	public void saveStepAttribute(ObjectId transformationId, ObjectId stepId, String code, double value) throws KettleException {
		try {
			session.getNodeByUUID(stepId.getId()).setProperty(code, value);
		} catch(Exception e) {
			throw new KettleException("Error saving step attribute ["+code+"] for step with id ["+stepId+"]", e);
		}
	}

	public void saveStepAttribute(ObjectId transformationId, ObjectId stepId, int nr, String code, String value) throws KettleException {
		try {
			session.getNodeByUUID(stepId.getId()).setProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr, value);
		} catch(Exception e) {
			throw new KettleException("Error saving step attribute ["+code+"] for step with id ["+stepId+"]", e);
		}
	}

	public void saveStepAttribute(ObjectId transformationId, ObjectId stepId, int nr, String code, boolean value) throws KettleException {
		try {
			session.getNodeByUUID(stepId.getId()).setProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr, value);
		} catch(Exception e) {
			throw new KettleException("Error saving step attribute ["+code+"] for step with id ["+stepId+"]", e);
		}
	}

	public void saveStepAttribute(ObjectId transformationId, ObjectId stepId, int nr, String code, long value) throws KettleException {
		try {
			session.getNodeByUUID(stepId.getId()).setProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr, value);
		} catch(Exception e) {
			throw new KettleException("Error saving step attribute ["+code+"] for step with id ["+stepId+"]", e);
		}
	}

	public void saveStepAttribute(ObjectId transformationId, ObjectId stepId, int nr, String code, double value) throws KettleException {
		try {
			session.getNodeByUUID(stepId.getId()).setProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr, value);
		} catch(Exception e) {
			throw new KettleException("Error saving step attribute ["+code+"] for step with id ["+stepId+"]", e);
		}
	}

	public boolean getStepAttributeBoolean(ObjectId stepId, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(stepId.getId()).getProperty(code).getBoolean();
		} catch(PathNotFoundException e) {
			return false;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get step attribute for step with id ["+stepId+"] and code ["+code+"]", e);
		}
	}
	
	public boolean getStepAttributeBoolean(ObjectId stepId, int nr, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(stepId.getId()).getProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr).getBoolean();
		} catch(PathNotFoundException e) {
			return false;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get step attribute for step with id ["+stepId+"] and code ["+code+"], nr="+nr, e);
		}
	}
	
	public boolean getStepAttributeBoolean(ObjectId stepId, int nr, String code, boolean def) throws KettleException { 
		try {
			return session.getNodeByUUID(stepId.getId()).getProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr).getBoolean();
		} catch(PathNotFoundException e) {
			return def;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get step attribute for step with id ["+stepId+"] and code ["+code+"], nr="+nr, e);
		}
	}
	
	public long getStepAttributeInteger(ObjectId stepId, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(stepId.getId()).getProperty(code).getLong();
		} catch(PathNotFoundException e) {
			return 0L;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get step attribute for step with id ["+stepId+"] and code ["+code+"]", e);
		}
	}
	
	public long getStepAttributeInteger(ObjectId stepId, int nr, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(stepId.getId()).getProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr).getLong();
		} catch(PathNotFoundException e) {
			return 0L;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get step attribute for step with id ["+stepId+"] and code ["+code+"], nr="+nr, e);
		}
	}
	
	public String getStepAttributeString(ObjectId stepId, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(stepId.getId()).getProperty(code).getString();
		} catch(PathNotFoundException e) {
			return null;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get step attribute for step with id ["+stepId+"] and code ["+code+"]", e);
		}
	}
	public String getStepAttributeString(ObjectId stepId, int nr, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(stepId.getId()).getProperty(code+PROPERTY_CODE_NR_SEPARATOR+nr).getString();
		} catch(PathNotFoundException e) {
			return null;
		} catch(RepositoryException e) {
			throw new KettleException("Unable to get step attribute for step with id ["+stepId+"] and code ["+code+"], nr="+nr, e);
		}
	}
	public Node getStepAttributeNode(ObjectId stepId, String code) throws KettleException { 
		try {
			return session.getNodeByUUID(stepId.getId()).getProperty(code).getNode();
		} catch(PathNotFoundException e) {
			return null;
		} catch (RepositoryException e) {
			throw new KettleException("Unable to get step attribute for step with id ["+stepId+"] and code ["+code+"]", e);
		}
	}

	
	
	
	
	public String getPropertyString(Node node, String code) throws KettleException {
		try {
			return node.getProperty(code).getString();
		} catch (PathNotFoundException e) {
			return null; // property is not defined
		} catch (RepositoryException e) {
			throw new KettleException("It was not possible to get information from property ["+code+"] in node", e);
		}
	}
	
	public long getPropertyLong(Node node, String code) throws KettleException {
		try {
			return node.getProperty(code).getLong();
		} catch (PathNotFoundException e) {
			return 0L; // property is not defined
		} catch (RepositoryException e) {
			throw new KettleException("It was not possible to get information from property ["+code+"] in node", e);
		}
	}
	
	public boolean getPropertyBoolean(Node node, String code) throws KettleException {
		try {
			return node.getProperty(code).getBoolean();
		} catch (PathNotFoundException e) {
			return false; // property is not defined
		} catch (RepositoryException e) {
			throw new KettleException("It was not possible to get information from property ["+code+"] in node", e);
		}
	}

	public boolean getPropertyBoolean(Node node, String code, boolean def) throws KettleException {
		try {
			return node.getProperty(code).getBoolean();
		} catch (PathNotFoundException e) {
			return def; // property is not defined, return the default
		} catch (RepositoryException e) {
			throw new KettleException("It was not possible to get information from property ["+code+"] in node", e);
		}
	}

	
	
	
	
	
	public void unlockJob(ObjectId jobId) throws KettleException {
		try {
			Node node = session.getNodeByUUID(jobId.getId());
			unlockNode(node); 
		} catch(Exception e) {
			throw new KettleException("Unable to unlock job with id ["+jobId+"]", e);
		}
	}

	/**
	 * @return the jcrRepositoryMeta
	 */
	public JCRRepositoryMeta getJcrRepositoryMeta() {
		return jcrRepositoryMeta;
	}

	/**
	 * @param jcrRepositoryMeta the jcrRepositoryMeta to set
	 */
	public void setJcrRepositoryMeta(JCRRepositoryMeta jcrRepositoryMeta) {
		this.jcrRepositoryMeta = jcrRepositoryMeta;
	}

	/**
	 * @return the jcrRepository
	 */
	public URLRemoteRepository getJcrRepository() {
		return jcrRepository;
	}

	/**
	 * @param jcrRepository the jcrRepository to set
	 */
	public void setJcrRepository(URLRemoteRepository jcrRepository) {
		this.jcrRepository = jcrRepository;
	}

	/**
	 * @param userInfo the userInfo to set
	 */
	public void setUserInfo(UserInfo userInfo) {
		this.userInfo = userInfo;
	}

	/**
	 * @return the workspace
	 */
	public Workspace getWorkspace() {
		return workspace;
	}

	/**
	 * @param workspace the workspace to set
	 */
	public void setWorkspace(Workspace workspace) {
		this.workspace = workspace;
	}

	/**
	 * @return the nodeTypeManager
	 */
	public NodeTypeManager getNodeTypeManager() {
		return nodeTypeManager;
	}

	/**
	 * @param nodeTypeManager the nodeTypeManager to set
	 */
	public void setNodeTypeManager(JackrabbitNodeTypeManager nodeTypeManager) {
		this.nodeTypeManager = nodeTypeManager;
	}

	/**
	 * @return the session
	 */
	public Session getSession() {
		return session;
	}
	
	public List<ObjectVersion> getVersions(RepositoryElementInterface element) throws KettleException {
		try {
			List<ObjectVersion> list = new ArrayList<ObjectVersion>();
			
			ObjectId objectId = element.getObjectId();
			if (objectId==null) {
				objectId = getObjectId(element.getName(), element.getRepositoryDirectory(), calcExtension(element));
				if (objectId==null) {
					throw new KettleException("Unable to find repository element ["+element+"]");
				}
			}
					
			Node node = session.getNodeByUUID(objectId.getId());
			VersionHistory versionHistory = node.getVersionHistory();
			Version version = versionHistory.getRootVersion();
			Version[] successors = version.getSuccessors();
			while (successors!=null && successors.length>0) {
				version = successors[0];
				successors = version.getSuccessors();
				list.add( getObjectVersion(version) );
			}
			
			return list;
		} catch(Exception e) {
			throw new KettleException("Could not retrieve version history of object with id ["+element+"]",e );
		}
	}
	
	public void undeleteObject(RepositoryElementInterface element) throws KettleException {
		try {
			Node node;
			if (element.getObjectId()==null) {
				node = getNode(element.getName(), element.getRepositoryDirectory(), calcExtension(element));
			} else {
				node = session.getNodeByUUID(element.getObjectId().getId());
			}

			boolean deleted = getPropertyBoolean(node, PROPERTY_DELETED, false);
			if (deleted) {
				// Undelete the last version, this again creates a new version.
				// We keep track of deletions this way.
				//
				node.checkout();
				node.setProperty(PROPERTY_DELETED, false);
				node.save();
				node.checkin();
			}
			
		} catch(Exception e) {
			throw new KettleException("There was an error un-deleting repository element ["+element+"]", e);
		}
	}

	
	ObjectVersion getObjectVersion(Version version) throws PathNotFoundException, RepositoryException {
		Node versionNode = getVersionNode(version);
		
		String comment = versionNode.getProperty(PROPERTY_VERSION_COMMENT).getString();
		String userCreated = versionNode.getProperty(PROPERTY_USER_CREATED).getString();


		return new JCRObjectVersion(version, comment, userCreated);
	}
	
	public Version getVersion(Node node, String versionLabel) throws KettleException {
		try {
			if (Const.isEmpty(versionLabel)) {
				return getLastVersion(node);
			} else {
				return node.getVersionHistory().getVersion(versionLabel);
			}
		} catch(VersionException e) {
			throw new KettleException("Unable to find version ["+versionLabel+"]", e);
		} catch(Exception e) {
			throw new KettleException("Error getting version ["+versionLabel+"]", e);
		}
	}

	/*
	 * Saves a relationship in the JCR.
	 * Please note that both parent and child nodes and elements have to be available and saved and versioned already.
	 * 
	 * @param parent
	 * @param parentNode
	 * @param child
	 * @param childNode
	 * @throws KettleException
	 *
	public Node saveRelationShip(RepositoryElementInterface parent, Node parentNode, RepositoryElementInterface child, Node childNode) throws KettleException {
		try {
			
			if (parentNode.equals(childNode) || parentNode.getUUID().equals(childNode.getUUID())) {
				throw new KettleException("Unable to create relationship between identical parent ["+parent+"] and child ["+child+"] (same UUID or same object)");
			}

			// We need references to the last version of parent and child!
			//
			Node lastParentNode = getVersionNode(getLastVersion(parentNode));
			Node lastChildNode = getVersionNode(getLastVersion(childNode));
			
			// Dump all the references in a ".used" folder alongside the object itself...
			//
			Node folderNode = findFolderNode(parent.getRepositoryDirectory());
			String nodeName = parent.getName()+calcExtension(parent)+".usage";
			
			// Create the node if it doesn't exist...
			//
			Node relFolderNode;
			try {
				relFolderNode = folderNode.getNode(nodeName);
			} catch(PathNotFoundException e) {
				relFolderNode = folderNode.addNode(nodeName, NODE_TYPE_UNSTRUCTURED);
			}
			
			parentNode.checkout();
			
			// OK, now put a new relationship in there...
			// The name of the relationship is the name of the child
			// This will lead to duplicates so we might as well add a version here...
			//
			String name = child.getName();
			name = name.replace(":", "_");
			Node node = relFolderNode.addNode(name, NODE_TYPE_RELATION);
			node.setProperty(PROPERTY_DESCRIPTION, "Relationship between parent ["+parent+"] and child ["+child+"]");
			node.setProperty(PROPERTY_PARENT_OBJECT, parentNode);
			node.setProperty(PROPERTY_CHILD_OBJECT, childNode);
			
			// Session is saved in the code calling this method!!
			//
			parentNode.save();
			return node;
		} catch(Exception e) {
			throw new KettleException("Unable to save relationship between parent ["+parent+"] and child ["+child+"]", e);
		}
	}
	*/

	/**
	 * An object is never deleted, simply marked as such!
	 * 
	 * @param id 
	 * @throws KettleException
	 */
	public void deleteObject(ObjectId id) throws KettleException {
		try {
			// What is the main object node?
			//
			Node node;
			
			try {
				node = session.getNodeByUUID(id.getId());
			} catch(ItemNotFoundException e) {
				// It's already gone!
				return;
			}
			
			deleteObject(node);
		}
		catch(Exception e) {
			throw new KettleException("Unable to mark object with ID ["+id+"] as deleted", e);
		}
	}
		

	/**
	 * An node is never deleted, simply marked as such!
	 * This is because of versioning reasons!
	 * 
	 * @param node
	 * @throws KettleException
	 */
	public void deleteObject(Node node) throws KettleException {
		try {
			node.checkout();
			node.setProperty(PROPERTY_DELETED, true);
			session.save();
			node.checkin();
		} catch(Exception e) {
			throw new KettleException("Unable to mark object as deleted", e);
		}
	}

	/**
	 * @return the rootNode
	 */
	public Node getRootNode() {
		return rootNode;
	}

	/**
	 * Returns a name. It is always required.
	 * @param node the node to get the name from
	 * @return The name of the node
	 * @throws Exception If no name property is found, an exception will be throws (PathNotFoundException)
	 */
	public String getObjectName(Node node) throws Exception {
		return node.getProperty(JCRRepository.PROPERTY_NAME).getString();
	}

	/**
	 * Returns the description of the node.  If no description is found, it returns null.
	 * @param node the node to get the description from
	 * @return the description of the node or null.
	 * @throws Exception In case something goes wrong (I/O, network, etc)
	 */
	public String getObjectDescription(Node node) throws Exception {
		return getPropertyString(node, JCRRepository.PROPERTY_DESCRIPTION);
	}

	public Node createOrVersionNode(RepositoryElementInterface element, String versionComment) throws Exception {
		String ext = calcExtension(element);
		String name = element.getName()+ext;
		Node folder = findFolderNode(element.getRepositoryDirectory());
		
		// First see if a node with the same name already exists...
		//
		Node node = null;
		RepositoryLock lock = null;
		
		ObjectId id = getObjectId(element.getName(), element.getRepositoryDirectory(), calcExtension(element));
		if (id!=null) {
			node = session.getNodeByUUID(id.getId());
			
			lock = getLock(id);
			
			if (lock!=null) {
				if (!getUserInfo().getLogin().equals(lock.getLogin())) {
					throw new KettleException("This object is locked by user ["+lock.getLogin()+"] @ "+lock.getLockDate()+" with message ["+lock.getMessage()+"], it needs to be unlocked before changes can be made.");
				} else {
					unlockNode(node);
				}
			}

			// We need to perform a check out to generate a new version
			// 
			node.checkout();
			
		} else {
			// Create a new node
			//
			node = folder.addNode(name, JCRRepository.NODE_TYPE_UNSTRUCTURED);
			node.addMixin(JCRRepository.MIX_VERSIONABLE);
			node.addMixin(JCRRepository.MIX_REFERENCEABLE);
		}
		
		node.setProperty(PROPERTY_DELETED, false);
		node.setProperty(PROPERTY_NAME, element.getName());
		node.setProperty(PROPERTY_DESCRIPTION, element.getDescription());
		node.setProperty(PROPERTY_VERSION_COMMENT, versionComment);
		node.setProperty(PROPERTY_USER_CREATED, userInfo.getLogin());

		return node;
	}	
}