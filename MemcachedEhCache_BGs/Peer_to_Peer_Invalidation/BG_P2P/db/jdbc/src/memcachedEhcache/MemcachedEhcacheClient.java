/**                                                                                                                                                                                
 * Copyright (c) 2012 USC Database Laboratory All rights reserved. 
 *
 * Authors:  Tarneet Singh Sidana
 * 
 * Code Changed from Authors: Sumita Barahmand and Shahram Ghandeharizadeh                                                                                                            
 *                                                                                                                                                                                 
 * Licensed under the Apache License, Version 2.0 (the "License"); you                                                                                                             
 * may not use this file except in compliance with the License. You                                                                                                                
 * may obtain a copy of the License at                                                                                                                                             
 *                                                                                                                                                                                 
 * http://www.apache.org/licenses/LICENSE-2.0                                                                                                                                      
 *                                                                                                                                                                                 
 * Unless required by applicable law or agreed to in writing, software                                                                                                             
 * distributed under the License is distributed on an "AS IS" BASIS,                                                                                                               
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or                                                                                                                 
 * implied. See the License for the specific language governing                                                                                                                    
 * permissions and limitations under the License. See accompanying                                                                                                                 
 * LICENSE file.                                                                                                                                                                   
 */

package memcachedEhcache;


import common.CacheUtilities;
import common.RdbmsUtilities;
import edu.usc.bg.base.ByteArrayByteIterator;
import edu.usc.bg.base.ByteIterator;
import edu.usc.bg.base.Client;
import edu.usc.bg.base.DB;
import edu.usc.bg.base.DBException;
import edu.usc.bg.base.ObjectByteIterator;
















































import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.ehcache.*;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.MemoryUnit;

import com.meetup.memcached.MemcachedClient;
import com.meetup.memcached.SockIOPool;

import org.apache.log4j.PropertyConfigurator;


/**
 * A class that wraps a JDBC compliant database to allow it to be interfaced with BG Core Classes.
 * This class extends {@link DB} and implements the database interface used by BG client.
 *
 * <br> Each client will have its own instance of this class. This client is
 * thread safe.
 *
 * <br> This interface expects a schema <key> <field1> <field2> <field3> ...
 * All attributes are of type VARCHAR. All accesses are through the primary key. Therefore,
 * only one index on the primary key is needed.
 *
 * <p> The following options must be passed when using this database client.
 *
 * <ul>
 * <li><b>db.driver</b> The JDBC driver class to use.</li>
 * <li><b>db.url</b> The Database connection URL.</li>
 * <li><b>db.user</b> User name for the connection.</li>
 * <li><b>db.passwd</b> Password for the connection.</li>
 * </ul>
 *
 * Author:  Shahram Ghandeharizadeh
 *
 */



public class MemcachedEhcacheClient extends DB implements MemcachedEhcacheClientConstants {

	/** Common Variables **/
	private static boolean startInvalidationServer = false;
	private boolean initialized = false;
	private static boolean verbose = false;
	public static Properties props;
	private static final String DEFAULT_PROP = "";
	private ConcurrentMap<Integer, PreparedStatement> newCachedStatements;
	private PreparedStatement preparedStatement;
	//private Statement statement;
	private Connection conn;
	private boolean isInsertImage;

	private static int GETFRNDCNT_STMT = 2;
	private static int GETPENDCNT_STMT = 3;
	private static int GETRESCNT_STMT = 4;
	private static int GETPROFILE_STMT = 5;
	private static int GETPROFILEIMG_STMT = 6;
	private static int GETFRNDS_STMT = 7;
	private static int GETFRNDSIMG_STMT = 8;
	private static int GETPEND_STMT = 9;
	private static int GETPENDIMG_STMT = 10;
	private static int REJREQ_STMT = 11;
	private static int ACCREQ_STMT = 12;
	private static int INVFRND_STMT = 13;
	private static int UNFRNDFRND_STMT = 14;
	private static int GETTOPRES_STMT = 15;
	private static int GETRESCMT_STMT = 16;
	private static int POSTCMT_STMT = 17;
	private static int DELCMT_STMT = 18;
	
	private static int IMAGE_SIZE_GRAN = 1024;
	private int THUMB_IMAGE_SIZE = 2*1024;
	
	private static AtomicInteger NumThreads = null;
	private static Semaphore crtcl = new Semaphore(1, true);
	
	/** EhCache Variables **/
	private final Ehcache ehem = null;
	public static Cache CM = null;
	private static String CacheName = "EhCache";
	private int MAX_CACHE_MEMORY = -1;
	
	/** Memcache Variables **/
	private static boolean ManageCache = false;
	private static final int CACHE_START_WAIT_TIME = 10000;
	//private static Vector<MemcachedClient> cacheclient_vector = new Vector<MemcachedClient>();
	SockIOPool cacheConnectionPool;
	private MemcachedClient cacheclient = null;
	
	private static String cache_hostname = "";
	private static Integer cache_port = -1;
	
	private static String invalidation_hostname = "";
	private static Integer invalidation_port = -1;
	
	StartProcess st;
	
	private static final int MAX_NUM_RETRIES = 10;
	private static final int TIMEOUT_WAIT_MILI = 100;
	public static final int CACHE_POOL_NUM_CONNECTIONS = 400;
	
	private static final double TTL_RANGE_PERCENT = 0.2; 
	private boolean useTTL = false;
	private int TTLvalue = 0;
	private boolean compressPayload = false;
	
	private static MemcachedEhcacheServer memcachedEhcacheServer;
	
	private String getCacheCmd()
	{		
		return "C:\\PSTools\\psexec \\\\"+cache_hostname+" -u shahram -p 2Shahram C:\\memcached\\memcached.exe -d start ";
	}
	
	private String getCacheStopCmd()
	{
		return "C:\\PSTools\\psexec \\\\"+cache_hostname+" -u shahram -p 2Shahram C:\\memcached\\memcached.exe -d stop ";
	}
	
	private static int incrementNumThreads() {
        int v;
        do {
            v = NumThreads.get();
        } while (!NumThreads.compareAndSet(v, v + 1));
        return v + 1;
    }
	
	private static int decrementNumThreads() {
        int v;
        do {
            v = NumThreads.get();
        } while (!NumThreads.compareAndSet(v, v - 1));
        return v - 1;
    }
 
	private void cleanupAllConnections() {
		try {
			//close all cached prepare statements
			Set<Integer> statementTypes = newCachedStatements.keySet();
			Iterator<Integer> it = statementTypes.iterator();
			while(it.hasNext()){
				int stmtType = it.next();
				if(newCachedStatements.get(stmtType) != null) newCachedStatements.get(stmtType).close();
			}
			if(conn != null) conn.close();
		} catch (SQLException e) {
			e.printStackTrace(System.out);
		}
	}

	/**
	 * Initialize the database connection and set it up for sending requests to the database.
	 * This must be called once per client.
	 * 
	 */
	@Override
	public boolean init() throws DBException 
	{
		if (initialized) 
		{
			System.out.println("Client connection already initialized.");
			
			/**Memcached Initialization Code **/
			cacheclient = new MemcachedClient();			
			crtcl.release();
			return true;
		}
		props = getProperties();
		String urls = props.getProperty(CONNECTION_URL, DEFAULT_PROP);
		String user = props.getProperty(CONNECTION_USER, DEFAULT_PROP);
		String passwd = props.getProperty(CONNECTION_PASSWD, DEFAULT_PROP);
		String driver = props.getProperty(DRIVER_CLASS);
		
		/**Memcached Init Code **/
		cache_hostname = props.getProperty(MEMCACHED_SERVER_HOST, MEMCACHED_SERVER_HOST_DEFAULT);
		cache_port = Integer.parseInt(props.getProperty(MEMCACHED_SERVER_PORT, MEMCACHED_SERVER_PORT_DEFAULT));
		
		invalidation_hostname = INVALIDATION_SERVER_HOST_DEFAULT;
		invalidation_port = Integer.parseInt(INVALIDATION_SERVER_PORT_DEFAULT);
		
		ManageCache = Boolean.parseBoolean(
				props.getProperty(MANAGE_CACHE_PROPERTY, 
						MANAGE_CACHE_PROPERTY_DEFAULT));
		
		TTLvalue = Integer.parseInt(props.getProperty(TTL_VALUE, TTL_VALUE_DEFAULT));
		useTTL = (TTLvalue != 0);
		
		compressPayload = Boolean.parseBoolean(props.getProperty(ENABLE_COMPRESSION_PROPERTY, ENABLE_COMPRESSION_PROPERTY_DEFAULT));

		/**Common Init Code **/
		isInsertImage = Boolean.parseBoolean(props.getProperty(Client.INSERT_IMAGE_PROPERTY, Client.INSERT_IMAGE_PROPERTY_DEFAULT));
		
		
		/**EhCache Init Code **/
		MAX_CACHE_MEMORY = Integer.parseInt(
				props.getProperty(
						MAX_CACHE_MEMORY_PROPERTY, 
						MAX_CACHE_MEMORY_DEFAULT));
		
		try {
			if (driver != null) {
				Class.forName(driver);
			}
			for (String url: urls.split(",")) {
				conn = DriverManager.getConnection(url, user, passwd);
				// Since there is no explicit commit method in the DB interface, all
				// operations should auto commit.
				conn.setAutoCommit(true);
			}
			newCachedStatements = new ConcurrentHashMap<Integer, PreparedStatement>();
		} catch (ClassNotFoundException e) {
			System.out.println("Error in initializing the JDBS driver: " + e);
			e.printStackTrace(System.out);
			return false;
		} catch (SQLException e) {
			System.out.println("Error in database operation: " + e);
			e.printStackTrace(System.out);
			return false;
		} catch (NumberFormatException e) {
			System.out.println("Invalid value for fieldcount property. " + e);
			e.printStackTrace(System.out);
			return false;
		}
		
		try {
			crtcl.acquire();
			
			if (CM == null){
				CacheManager singletonManager = CacheManager.create(
						new Configuration().maxBytesLocalHeap(
								MAX_CACHE_MEMORY, MemoryUnit.MEGABYTES));
				Cache memoryOnlyCache = new Cache(CacheName, 0, false, true, 60*1000, 60*1000);
				singletonManager.addCache(memoryOnlyCache);
				CM = singletonManager.getCache(CacheName);
			
				String[] cacheNames = CacheManager.getInstance().getCacheNames();
				System.out.print("Caches are:");
				for (int i = 0; i < cacheNames.length; i++)
					System.out.print(" "+cacheNames[i]);
				System.out.println("");
				CM = singletonManager.getCache(CacheName);
			}

			if(NumThreads == null)
			{
				NumThreads = new AtomicInteger();
				NumThreads.set(0);
			}
			incrementNumThreads();	
			
			Properties prop = new Properties();
			prop.setProperty("log4j.rootLogger", "ERROR, A1");
			//prop.setProperty("log4j.appender.A1", "org.apache.log4j.ConsoleAppender");
			prop.setProperty("log4j.appender.A1", "org.apache.log4j.FileAppender");
			prop.setProperty("log4j.appender.A1.File", "whalincache.out");
			prop.setProperty("log4j.appender.A1.Append", "false");
			prop.setProperty("log4j.appender.A1.layout", "org.apache.log4j.PatternLayout");
			prop.setProperty("log4j.appender.A1.layout.ConversionPattern", "%-4r %-5p [%t] %37c %3x - %m%n");
			PropertyConfigurator.configure(prop);
			
			String[] servers = { cache_hostname + ":" + cache_port };
			cacheConnectionPool = SockIOPool.getInstance();
			cacheConnectionPool.setServers( servers );
			cacheConnectionPool.setFailover( true );
			cacheConnectionPool.setInitConn( 10 ); 
			cacheConnectionPool.setMinConn( 5 );
			cacheConnectionPool.setMaxConn( CACHE_POOL_NUM_CONNECTIONS );
			cacheConnectionPool.setMaintSleep( 30 );
			cacheConnectionPool.setNagle( false );
			cacheConnectionPool.setSocketTO( 3000 );
			cacheConnectionPool.setAliveCheck( true );
			cacheConnectionPool.initialize();
			
			if (ManageCache) 
			{
				System.out.println("Starting Cache: "+this.getCacheCmd());
				//this.st = new StartCOSAR(this.cache_cmd + (RaysConfig.cacheServerPort + i), "cache_output" + i + ".txt"); 
				this.st = new StartProcess(this.getCacheCmd(), "cache_output.txt");
				this.st.start();

				System.out.println("Wait for "+CACHE_START_WAIT_TIME/1000+" seconds to allow Cache to startup.");
				try{
					Thread.sleep(CACHE_START_WAIT_TIME);
				}catch(Exception e)
				{
					e.printStackTrace(System.out);
				}
			}

			if(!startInvalidationServer)
			{
				System.out.println("Creating a new memcachedEhcache Server...");
				memcachedEhcacheServer = new MemcachedEhcacheServer(LOCAL_MEMCACHEDEHCACHE_SERVER_PORT_DEFAULT);
				memcachedEhcacheServer.start();
				startInvalidationServer = true;
			}
			
			initialized = true;
			cacheclient = new MemcachedClient();
			crtcl.release();
			
		} catch (Exception e){
			System.out.println("SQLTrigQR init failed to acquire semaphore.");
			e.printStackTrace(System.out);
			return false;
		}
		return true;
	}
		

	@Override
	public void cleanup(boolean warmup) {		
		try {
			if (verbose) System.out.println("************Cleanup before calling decrement, number of threads="+NumThreads);
			if (warmup) decrementNumThreads();
			if (verbose) System.out.println("Cleanup (before warmup-chk):  NumThreads="+NumThreads);
			if(!warmup){
				crtcl.acquire();
								
				decrementNumThreads();
				if (verbose) System.out.println("Cleanup (after warmup-chk):  NumThreads="+NumThreads);
				if (NumThreads.get() > 0){
					crtcl.release();
					if (verbose) System.out.println("Leave without cleaning up.  A remaining clients will clean up the cache manager.");
					return;
				}
				
				SockIOPool cacheConnectionPool = SockIOPool.getInstance();
				cacheConnectionPool.shutDown();
				

				if (ManageCache){
					//MemcachedClient cache_conn = new MemcachedClient(COSARServer.cacheServerHostname, COSARServer.cacheServerPort);			
					//cache_conn.shutdownServer();
					System.out.println("Stopping Cache: "+this.getCacheStopCmd());
					//this.st = new StartCOSAR(this.cache_cmd + (RaysConfig.cacheServerPort + i), "cache_output" + i + ".txt"); 
					this.st = new StartProcess(this.getCacheStopCmd(), "cache_output.txt");
					this.st.start();
					System.out.print("Waiting for Cache to finish.");

					if( this.st != null )
						this.st.join();
					Thread.sleep(10000);
					System.out.println("..Done!");
				}

				startInvalidationServer=false;
				memcachedEhcacheServer.cleanUp();
				
				cleanupAllConnections();
				
				//Shutdown Ehcache
				CacheManager.getInstance().shutdown();

				crtcl.release();
			}
		} catch (Exception e) {
			System.out.println("Error in closing the connection. " + e);
			//throw new DBException(e);
		}
	}

	private PreparedStatement createAndCacheStatement(int stmttype, String query) throws SQLException{
		PreparedStatement newStatement = conn.prepareStatement(query);
		PreparedStatement stmt = newCachedStatements.putIfAbsent(stmttype, newStatement);
		if (stmt == null) return newStatement;
		else return stmt;
	}
	
	@Override
	//Load phase query not cached
	public int insertEntity(String entitySet, String entityPK, HashMap<String, ByteIterator> values, boolean insertImage) {
		return RdbmsUtilities.insertEntity(entitySet, entityPK, values, insertImage, conn, "");
	}
	
	private boolean CacheEMDelete(String key)
	{
		boolean result = true;
		if(verbose) {
			System.out.println("Deleting key: " + key);
		}
		// Not checking for result of remove because delete
		//  may be called against a key that doesn't exist.
		CM.remove(key);
		return result;
	}


	
	@Override
	public int acceptFriend(int inviterID, int inviteeID) {

		int retVal = SUCCESS;
		if(inviterID < 0 || inviteeID < 0)
			return -1;
		String query;
		query = "UPDATE friendship SET status = 2 WHERE inviterid=? and inviteeid= ? ";
		try {
			//preparedStatement = conn.prepareStatement(query);
			if((preparedStatement = newCachedStatements.get(ACCREQ_STMT)) == null){
				preparedStatement = createAndCacheStatement(ACCREQ_STMT, query);
			}
			preparedStatement.setInt(1, inviterID);
			preparedStatement.setInt(2, inviteeID);
			preparedStatement.executeUpdate();

			String key;

			if(isInsertImage)
			{
				key = "lsFrds:"+inviterID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}

				key = "lsFrds:"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}

				//Invalidate the friendcount for each member
				key = "profile"+inviterID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}

				key = "profile"+inviteeID;
				if(!CacheEMDelete(key))
				{
					
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}

				key = "viewPendReq:"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}
				
				key = "ownprofile"+inviterID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}

				key = "ownprofile"+inviteeID;
				if(!CacheEMDelete(key))
				{
					
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}
			}
			else
			{
				key = "lsFrdsNoImage:"+inviterID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println(" acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}

				key = "lsFrdsNoImage:"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}

				//Invalidate the friendcount for each member
				key = "profileNoImage"+inviterID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}

				key = "profileNoImage"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}
				
				key = "ownprofileNoImage"+inviterID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}

				key = "ownprofileNoImage"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}

				key = "viewPendReqNoImage:"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("acceptFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}
			}
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}
		catch (Exception e1) {
			e1.printStackTrace(System.out);
			System.exit(-1);
		}
		finally{
			try {
				if(preparedStatement != null)
					preparedStatement.clearParameters();
				//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		return retVal;		
	}
	
	@Override
	public int viewProfile(int requesterID, int profileOwnerID,
			HashMap<String, ByteIterator> result, boolean insertImage, boolean testMode) {

		if (verbose) System.out.print("Get Profile "+requesterID+" "+profileOwnerID);
		ResultSet rs = null;
		int retVal = SUCCESS;
		if(requesterID < 0 || profileOwnerID < 0)
			return -1;
		
		byte[] payload;
		String key;
		String query="";
		
		HashMap<String, byte[]> CacheEntry = null;
		HashMap<String, ByteIterator> SR = null;
		
		// Check Cache first
		if(insertImage)
		{
			key = "profile"+profileOwnerID;
		}
		else
		{
			key = "profileNoImage"+profileOwnerID;
		}
		
		if(requesterID == profileOwnerID)
		{
			key = "own" + key;
		}
		
		try {
			Element elt = CM.get(key);
			if (elt != null){
				Object value = elt.getObjectValue();
				if ( value != null ){
					if (verbose) System.out.println("Hit!  Elts in memory:  "+CM.getMemoryStoreSize()+", Elts in DiskStore "+CM.getDiskStoreSize());
					HashMap<String, byte[]> CMEntry = ( HashMap<String, byte[]> ) value;
					for (Map.Entry<String, byte[]> entry : CMEntry.entrySet()) { 
						if (verbose) System.out.println("key = "+entry.getKey()+", value = "+new String(entry.getValue()));
						
						if ( ! entry.getKey().equalsIgnoreCase("pic") )
							result.put(entry.getKey(), new ObjectByteIterator(entry.getValue()));
			        }
					return retVal;
				}
			}
			else
			{
				payload = common.CacheUtilities.CacheEMGet(this.cacheclient, key, this.compressPayload);
				
				if (payload != null && CacheUtilities.unMarshallHashMap(result, payload))
				{
					if (verbose) System.out.println("... Cache Hit!");
						
					//cacheclient.shutdown();
					return retVal;
				} 
				else if (verbose) System.out.println("... Query DB.");	
			}
		} catch (Exception e1) {
			e1.printStackTrace(System.out);
			System.exit(-1);
		} 
		
		CacheEntry = new HashMap<String, byte[]>();
		SR = new HashMap<String, ByteIterator>(); 
			

		try 
		{
			if (verbose) System.out.print("... Query DB!");

			query = "SELECT count(*) FROM  friendship WHERE (inviterID = ? OR inviteeID = ?) AND status = 2 ";
			
			if((preparedStatement = newCachedStatements.get(GETFRNDCNT_STMT)) == null)
				preparedStatement = createAndCacheStatement(GETFRNDCNT_STMT, query);
			
			preparedStatement.setInt(1, profileOwnerID);
			preparedStatement.setInt(2, profileOwnerID);

			rs = preparedStatement.executeQuery();

			String Value="0";
			if (rs.next()){
				Value = rs.getString(1);
				CacheEntry.put("friendcount", Value.getBytes());
				SR.put("friendcount", new ObjectByteIterator(rs.getString(1).getBytes()));
				result.put("friendcount", new ObjectByteIterator(rs.getString(1).getBytes())) ;
			}
			else 
			{
				CacheEntry.put("friendcount", "0".getBytes());
				SR.put("friendcount", new ObjectByteIterator("0".getBytes())) ;
				result.put("friendcount", new ObjectByteIterator("0".getBytes())) ;
			}

		}
		catch(SQLException sx)
		{
			retVal = -2;
			sx.printStackTrace(System.out);
		}
		finally
		{
			try {
				if (rs != null)
					rs.close();
				if(preparedStatement != null)
					preparedStatement.clearParameters();
			} catch (SQLException e) {
				e.printStackTrace(System.out);
				retVal = -2;
			}
		}

		//pending friend request count
		//if owner viwing her own profile, she can view her pending friend requests
		if(requesterID == profileOwnerID){

			try {
				query = "SELECT count(*) FROM  friendship WHERE inviteeID = ? AND status = 1 ";
				//preparedStatement = conn.prepareStatement(query);
				if((preparedStatement = newCachedStatements.get(GETPENDCNT_STMT)) == null)
					preparedStatement = createAndCacheStatement(GETPENDCNT_STMT, query);
				
				preparedStatement.setInt(1, profileOwnerID);
				rs = preparedStatement.executeQuery();
				String Value = "0";
				if (rs.next()){
					Value = rs.getString(1);
					CacheEntry.put("pendingcount", Value.getBytes());
					SR.put("pendingcount", new ObjectByteIterator(rs.getString(1).getBytes())) ;
					result.put("pendingcount", new ObjectByteIterator(rs.getString(1).getBytes())) ;
				}
				else {
					CacheEntry.put("pendingcount", "0".getBytes());
					SR.put("pendingcount", new ObjectByteIterator("0".getBytes())) ;
					result.put("pendingcount", new ObjectByteIterator("0".getBytes())) ;
				}

			}catch(SQLException sx){
				retVal = -2;
				sx.printStackTrace(System.out);
			}finally{
				try {
					if (rs != null)
						rs.close();
					if(preparedStatement != null)
						preparedStatement.clearParameters();
						//preparedStatement.close();
				} catch (SQLException e) {
					e.printStackTrace(System.out);
					retVal = -2;
				}
			}
		}
		
		//resource count
		query = "SELECT count(*) FROM  resources WHERE wallUserID = ?";

		try {
			//preparedStatement = conn.prepareStatement(query);
			if((preparedStatement = newCachedStatements.get(GETRESCNT_STMT)) == null)
				preparedStatement = createAndCacheStatement(GETRESCNT_STMT, query);
			
			preparedStatement.setInt(1, profileOwnerID);
			//cacheclient.addQuery("SELECT count(*) FROM  resources WHERE wallUserID = "+profileOwnerID);
			rs = preparedStatement.executeQuery();
			if (rs.next()){
				//SR.put("resourcecount", new StringByteIterator(rs.getString(1))) ;
				CacheEntry.put("resourcecount", rs.getString(1).getBytes());
				SR.put("resourcecount", new ObjectByteIterator(rs.getString(1).getBytes())) ;
				result.put("resourcecount", new ObjectByteIterator(rs.getString(1).getBytes())) ;
			} else {
				//SR.put("resourcecount", new StringByteIterator("0")) ;
				CacheEntry.put("resourcecount", "0".getBytes());
				SR.put("resourcecount", new ObjectByteIterator("0".getBytes())) ;
				result.put("resourcecount", new ObjectByteIterator("0".getBytes())) ;
			}
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}finally{
			try {
				if (rs != null)
					rs.close();
				if(preparedStatement != null)
					preparedStatement.clearParameters();
					//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		
		try {
			if(insertImage){
				query = "SELECT pic, userid,username, fname, lname, gender, dob, jdate, ldate, address, email, tel FROM  users WHERE UserID = ?";
				
				if((preparedStatement = newCachedStatements.get(GETPROFILEIMG_STMT)) == null)
					preparedStatement = createAndCacheStatement(GETPROFILEIMG_STMT, query);
				
			}
			else{
				query = "SELECT userid,username, fname, lname, gender, dob, jdate, ldate, address, email, tel FROM  users WHERE UserID = ?";
				
				if((preparedStatement = newCachedStatements.get(GETPROFILE_STMT)) == null)
					preparedStatement = createAndCacheStatement(GETPROFILE_STMT, query);
				
			}
			//preparedStatement = conn.prepareStatement(query);
			preparedStatement.setInt(1, profileOwnerID);
			
			rs = preparedStatement.executeQuery();
			ResultSetMetaData md = rs.getMetaData();
			int col = md.getColumnCount();
			if(rs.next()){
				for (int i = 1; i <= col; i++){
					String col_name = md.getColumnName(i);
					String value ="";
					if(col_name.equalsIgnoreCase("pic")){
						// Get as a BLOB
						Blob aBlob = rs.getBlob(col_name);
						byte[] allBytesInBlob = aBlob.getBytes(1, (int) aBlob.length());
						
						if(testMode){
							//dump to file
							try{
								FileOutputStream fos = new FileOutputStream(profileOwnerID+"-ctprofimage.bmp");
								fos.write(allBytesInBlob);
								fos.close();
							}catch(Exception ex){
							}
						}
						
						CacheEntry.put(col_name,allBytesInBlob);
						SR.put(col_name, new ObjectByteIterator(allBytesInBlob));
						result.put(col_name, new ByteArrayByteIterator(allBytesInBlob));
					}
					else
					{
						value = rs.getString(col_name);

						CacheEntry.put(col_name, value.getBytes());
						SR.put(col_name, new ObjectByteIterator(value.getBytes()));
						result.put(col_name, new ObjectByteIterator(value.getBytes()));
					}
				}
			}

		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}finally{
			try {
				if (rs != null)
					rs.close();
				if(preparedStatement != null)
					preparedStatement.clearParameters();
					//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		
		//Insert the data structure in the ehCache.
		if (CM != null && CacheEntry != null){
			if (verbose) System.out.println("Popualte the cache:");
			CM.put(new Element(key, CacheEntry));
		}
		
		//serialize the result hashmap and insert it in the cache for future use
		payload = CacheUtilities.SerializeHashMap(SR);
		try 
		{
			if(!common.CacheUtilities.CacheEMSet(this.cacheclient, key, payload, this.useTTL, this.TTLvalue, this.compressPayload))
			{
				throw new Exception("Error calling WhalinMemcached set");
			}
		} 
		catch (Exception e1) 
		{
			System.out.println("Error in ApplicationCacheClient, failed to insert the key-value pair in the cache.");
			e1.printStackTrace(System.out);
			retVal = -2;
		}
		
		return retVal;
	}

	@Override
	public int listFriends(int requesterID, int profileOwnerID,
			Set<String> fields, Vector<HashMap<String, ByteIterator>> result, boolean insertImage, boolean testMode) {
		
		if (verbose) System.out.print("List friends... "+profileOwnerID);
		
		int retVal = SUCCESS;
		ResultSet rs = null;
		if(requesterID < 0 || profileOwnerID < 0)
			return -1;
		

		String key;
		String query="";
		Vector<HashMap<String, byte[]>> FrdList = null;
		HashMap<String, byte[]> OneFrd = null;
		
		if(insertImage)
		{
			key = "lsFrds:"+profileOwnerID;
		}
		else
		{
			key = "lsFrdsNoImage:"+profileOwnerID;
		}
		try 
		{
			Element elt = CM.get(key);
			
			if (elt != null)
			{
				Object value = elt.getObjectValue();
				
				if ( value != null )
				{
					if (verbose) System.out.println("Hit!  Elts in memory:  "+CM.getMemoryStoreSize()+", Elts in DiskStore "+CM.getDiskStoreSize());
					
					Vector<HashMap<String, byte[]>> CMEntry = ( Vector<HashMap<String, byte[]>> ) value;
					
					for (int i=0; i < CMEntry.size(); i++)
					{
						HashMap<String, ByteIterator> resElt = new HashMap<String, ByteIterator>();
						HashMap<String, byte[]> CacheElt = CMEntry.elementAt(i);
						for (Map.Entry<String, byte[]> entry : CacheElt.entrySet()) { 
							if (verbose) System.out.println("key = "+entry.getKey()+", value = "+new String(entry.getValue()));

							if ( ! entry.getKey().equalsIgnoreCase("tpic") )
								resElt.put(entry.getKey(), new ObjectByteIterator(entry.getValue()));
						}
						result.add(resElt);
					}
					return retVal;
				}
			} 
			else
			{
				byte[] payload = common.CacheUtilities.CacheEMGet(this.cacheclient, key, this.compressPayload);
				if (payload != null){
					if (verbose) System.out.println("... Cache Hit!");
					if (!CacheUtilities.unMarshallVectorOfHashMaps(payload,result))
						System.out.println("Error in ApplicationCacheClient: Failed to unMarshallVectorOfHashMaps");
					
					//cacheclient.shutdown();
					return retVal;
				}
				
				else if (verbose) System.out.println("... Query DB.");
			}
		} catch (Exception e1) {
			e1.printStackTrace(System.out);
			System.exit(-1);
		} 
		
		FrdList = new Vector< HashMap<String, byte[]> >();
		OneFrd = new HashMap<String, byte[]>();
			
		try {
			if(insertImage){
				query = "SELECT userid,inviterid, inviteeid, username, fname, lname, gender, dob, jdate, ldate, address,email,tel,tpic FROM users, friendship WHERE ((inviterid=? and userid=inviteeid) or (inviteeid=? and userid=inviterid)) and status = 2";
				if((preparedStatement = newCachedStatements.get(GETFRNDSIMG_STMT)) == null)
					preparedStatement = createAndCacheStatement(GETFRNDSIMG_STMT, query);	
				
			}else{
				query = "SELECT userid,inviterid, inviteeid, username, fname, lname, gender, dob, jdate, ldate, address,email,tel FROM users, friendship WHERE ((inviterid=? and userid=inviteeid) or (inviteeid=? and userid=inviterid)) and status = 2";
				if((preparedStatement = newCachedStatements.get(GETFRNDS_STMT)) == null)
					preparedStatement = createAndCacheStatement(GETFRNDS_STMT, query);	
			}
			//preparedStatement = conn.prepareStatement(query);
			preparedStatement.setInt(1, profileOwnerID);
			preparedStatement.setInt(2, profileOwnerID);
			rs = preparedStatement.executeQuery();
			int cnt =0;
			while (rs.next()){
				cnt++;
				
				HashMap<String, ByteIterator> values = new HashMap<String, ByteIterator>();
				OneFrd = new HashMap<String, byte[]>();
				
				if (fields != null) {
					for (String field : fields) {
						String value = rs.getString(field);
						if(field.equalsIgnoreCase("userid"))
							field = "userid";
						if(field.equalsIgnoreCase("tpic")){
							// Get as a BLOB
							Blob aBlob = rs.getBlob(field);
							byte[] allBytesInBlob = aBlob.getBytes(1, (int) aBlob.length());
							OneFrd.put(field,allBytesInBlob);
							if(testMode){
								//dump to file
								try{
									FileOutputStream fos = new FileOutputStream(profileOwnerID+"-"+cnt+"-thumbimage.bmp");
									fos.write(allBytesInBlob);
									fos.close();
								}catch(Exception ex){
								}
							}
							values.put(field, new ObjectByteIterator(allBytesInBlob));
						} else {
							values.put(field, new ObjectByteIterator(value.getBytes()));
							OneFrd.put(field, value.getBytes());
						}
					}
					FrdList.add(OneFrd);
					result.add(values);
				}else{
					//get the number of columns and their names
					//Statement st = conn.createStatement();
					//ResultSet rst = st.executeQuery("SELECT * FROM users");
					ResultSetMetaData md = rs.getMetaData();
					int col = md.getColumnCount();
					for (int i = 1; i <= col; i++){
						String col_name = md.getColumnName(i);
						String value="";
						if(col_name.equalsIgnoreCase("tpic")){
							// Get as a BLOB
							Blob aBlob = rs.getBlob(col_name);
							byte[] allBytesInBlob = aBlob.getBytes(1, (int) aBlob.length());
							OneFrd.put(col_name,allBytesInBlob);
							if(testMode){
								//dump to file
								try{
									FileOutputStream fos = new FileOutputStream(profileOwnerID+"-"+cnt+"-thumbimage.bmp");
									fos.write(allBytesInBlob);
									fos.close();
								}catch(Exception ex){
								}
							}
							values.put(col_name, new ObjectByteIterator(allBytesInBlob));
						}else{
							value = rs.getString(col_name);
							if(col_name.equalsIgnoreCase("userid"))
								col_name = "userid";
							OneFrd.put(col_name,value.getBytes());
							values.put(col_name, new ObjectByteIterator(value.getBytes()));
						}
					}
					FrdList.add(OneFrd);
					result.add(values);
				}
			}
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}finally{
			try {
				if (rs != null)
					rs.close();
				if(preparedStatement != null)
					preparedStatement.clearParameters();
					//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		
		//Insert the data structure in the cache.
		if (CM != null && FrdList != null){
			if (verbose) System.out.println("Popualte the cache with FrdList.");
			CM.put(new Element(key, FrdList));
		}
		
		//serialize the result hashmap and insert it in the cache for future use
		byte[] payload = CacheUtilities.SerializeVectorOfHashMaps(result);
		try {
			if(!common.CacheUtilities.CacheEMSet(this.cacheclient, key, payload, this.useTTL, this.TTLvalue, this.compressPayload))
			{
				throw new Exception("Error calling WhalinMemcached set");
			}
			//cacheclient.shutdown();
		} catch (Exception e1) {
			System.out.println("Error in ApplicationCacheClient, getListOfFriends failed to insert the key-value pair in the cache.");
			e1.printStackTrace(System.out);
			retVal = -2;
		}

		return retVal;		
	}

	@Override
	public int viewFriendReq(int profileOwnerID,
			Vector<HashMap<String, ByteIterator>> result, boolean insertImage, boolean testMode) {

		int retVal = SUCCESS;
		ResultSet rs = null;
		if(profileOwnerID < 0)
			return -1;
		
		String key;
		String query="";
		Vector<HashMap<String, byte[]>> FrdList = null;
		HashMap<String, byte[]> OneFrd = null;
		
		if(insertImage)
		{
			key = "viewPendReq:"+profileOwnerID;
		}
		else
		{
			key = "viewPendReqNoImage:"+profileOwnerID;
		}
		try {
			Element elt = CM.get(key);
			if (elt != null){
				Object value = elt.getObjectValue();
				if ( value != null ){
					if (verbose) System.out.println("Hit!  Elts in memory:  "+CM.getMemoryStoreSize()+", Elts in DiskStore "+CM.getDiskStoreSize());
					Vector<HashMap<String, byte[]>> CMEntry = ( Vector<HashMap<String, byte[]>> ) value;
					for (int i=0; i < CMEntry.size(); i++){
						HashMap<String, ByteIterator> resElt = new HashMap<String, ByteIterator>();
						HashMap<String, byte[]> CacheElt = CMEntry.elementAt(i);
						for (Map.Entry<String, byte[]> entry : CacheElt.entrySet()) { 
							if (verbose) System.out.println("key = "+entry.getKey()+", value = "+new String(entry.getValue()));

							if ( ! entry.getKey().equalsIgnoreCase("tpic") )
								resElt.put(entry.getKey(), new ObjectByteIterator(entry.getValue()));
						}
						result.add(resElt);
					}
					return retVal;
				}
			}
			else
			{
				byte[] payload = common.CacheUtilities.CacheEMGet(this.cacheclient, key, this.compressPayload);
				
				if (payload != null)
				{
					if (!CacheUtilities.unMarshallVectorOfHashMaps(payload,result))
						System.out.println("Error in ApplicationCacheClient: Failed to unMarshallVectorOfHashMaps");
					if (verbose) System.out.println("... Cache Hit!");
					//cacheclient.shutdown();
					return retVal;
				}
				
				else if (verbose) System.out.println("... Query DB.");
			}
		} catch (Exception e1) {
			e1.printStackTrace(System.out);
			System.exit(-1);
		} 
		
		FrdList = new Vector< HashMap<String, byte[]> >();
		OneFrd = new HashMap<String, byte[]>();
		
		try {
			if(insertImage){
				query = "SELECT userid, inviterid, inviteeid, username, fname, lname, gender, dob, jdate, ldate, address,email,tel,tpic FROM users, friendship WHERE inviteeid=? and status = 1 and inviterid = userid";
				if((preparedStatement = newCachedStatements.get(GETPENDIMG_STMT)) == null)
					preparedStatement = createAndCacheStatement(GETPENDIMG_STMT, query);	
				
			}else {
				query = "SELECT userid,inviterid, inviteeid, username, fname, lname, gender, dob, jdate, ldate, address,email,tel FROM users, friendship WHERE inviteeid=? and status = 1 and inviterid = userid";
				if((preparedStatement = newCachedStatements.get(GETPEND_STMT)) == null)
					preparedStatement = createAndCacheStatement(GETPEND_STMT, query);		
			}
			//preparedStatement = conn.prepareStatement(query);
			preparedStatement.setInt(1, profileOwnerID);
			rs = preparedStatement.executeQuery();
			int cnt=0;
			while (rs.next()){
				cnt++;
				
				HashMap<String, ByteIterator> values = new HashMap<String, ByteIterator>();
				OneFrd = new HashMap<String, byte[]>();
				
				//get the number of columns and their names
				ResultSetMetaData md = rs.getMetaData();
				int col = md.getColumnCount();
				for (int i = 1; i <= col; i++){
					String col_name = md.getColumnName(i);
					String value = "";
					if(col_name.equalsIgnoreCase("tpic")){
						// Get as a BLOB
						Blob aBlob = rs.getBlob(col_name);
						byte[] allBytesInBlob = aBlob.getBytes(1, (int) aBlob.length());
						OneFrd.put(col_name,allBytesInBlob);
						if(testMode){
							//dump to file
							try{
								FileOutputStream fos = new FileOutputStream(profileOwnerID+"-"+cnt+"-thumbimage.bmp");
								fos.write(allBytesInBlob);
								fos.close();
							}catch(Exception ex){
							}

						}
						values.put(col_name, new ObjectByteIterator(allBytesInBlob));
					}else{
						value = rs.getString(col_name);
						if(col_name.equalsIgnoreCase("userid"))
							col_name = "userid";
						OneFrd.put(col_name,value.toString().getBytes());
						values.put(col_name, new ObjectByteIterator(value.getBytes()));
					}
				}
				FrdList.add(OneFrd);
				result.add(values);
			}
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}finally{
			try {
				if (rs != null)
					rs.close();
				if(preparedStatement != null)
					preparedStatement.clearParameters();
					//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		
		//Insert the data structure in the cache.
		if (CM != null && FrdList != null){
			if (verbose) System.out.println("Popualte the cache with FrdList.");
			CM.put(new Element(key, FrdList));
		}
		
		byte[] payload = CacheUtilities.SerializeVectorOfHashMaps(result);
		try {
			if(!common.CacheUtilities.CacheEMSet(this.cacheclient, key, payload, this.useTTL, this.TTLvalue, this.compressPayload))
			{
				throw new Exception("Error calling WhalinMemcached set");
			}
			//cacheclient.shutdown();
		} catch (Exception e1) {
			System.out.println("Error in ApplicationCacheClient, getListOfFriends failed to insert the key-value pair in the cache.");
			e1.printStackTrace(System.out);
			retVal = -2;
		}

		return retVal;		
	}

	@Override
	public int rejectFriend(int inviterID, int inviteeID) {
		int retVal = SUCCESS;
		if(inviterID < 0 || inviteeID < 0)
			return -1;

		//System.out.println(inviterID+" "+inviteeID);
		String query = "DELETE FROM friendship WHERE inviterid=? and inviteeid= ? and status=1";
		try {
			if((preparedStatement = newCachedStatements.get(REJREQ_STMT)) == null)
				preparedStatement = createAndCacheStatement(REJREQ_STMT, query);
			//preparedStatement = conn.prepareStatement(query);
			preparedStatement.setInt(1, inviterID);
			preparedStatement.setInt(2, inviteeID);
			preparedStatement.executeUpdate();
			String key;
			if(isInsertImage)
			{
				key = "ownprofile"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("rejectFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}
	
				key = "viewPendReq:"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("rejectFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}
			}
			else
			{
				key = "ownprofileNoImage"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("rejectFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}
	
				key = "viewPendReqNoImage:"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("rejectFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}
				}
			}
			
			key = "PendingFriendship:"+inviteeID;
			if(!CacheEMDelete(key))
			{
				if (verbose) System.out.println("rejectFriend failed to delete Ehcache key "+key);
			}
			if (!useTTL)
			{
				if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
				{
					throw new Exception("Error calling WhalinMemcached delete");
				}
			}
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}
		catch (Exception e1) {
			e1.printStackTrace(System.out);
			System.exit(-1);
		}
		finally{
			try {
				if(preparedStatement != null)
					preparedStatement.clearParameters();
					//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		return retVal;	
	}

	@Override
	//Load phase query not cached
	public int CreateFriendship(int memberA, int memberB) {
		int retVal = SUCCESS;
		if(memberA < 0 || memberB < 0)
			return -1;
		try {
			String DML = "INSERT INTO friendship values(?,?,2)";
			preparedStatement = conn.prepareStatement(DML);
			preparedStatement.setInt(1, memberA);
			preparedStatement.setInt(2, memberB);
			preparedStatement.executeUpdate();
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}finally{
			try {
				if(preparedStatement != null)
					preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		return retVal;
	}

	@Override
	public int inviteFriend(int inviterID, int inviteeID) {
		int retVal = SUCCESS;
		if(inviterID < 0 || inviteeID < 0)
			return -1;
		String query = "INSERT INTO friendship values(?,?,1)";
		try {
			//preparedStatement = conn.prepareStatement(query);
			if((preparedStatement = newCachedStatements.get(INVFRND_STMT)) == null)
				preparedStatement = createAndCacheStatement(INVFRND_STMT, query);	
			preparedStatement.setInt(1, inviterID);
			preparedStatement.setInt(2, inviteeID);
			preparedStatement.executeUpdate();
			
			String key;
			if(isInsertImage)
			{
				key = "ownprofile"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("inviteFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
	
				key = "viewPendReq:"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("inviteFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
			}
			else
			{
				key = "ownprofileNoImage"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("inviteFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
	
				key = "viewPendReqNoImage:"+inviteeID;
				if(!CacheEMDelete(key))
				{
					if (verbose) System.out.println("inviteFriend failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
			}
			
			key = "PendingFriendship:"+inviteeID;
			if(!CacheEMDelete(key))
			{
				if (verbose) System.out.println("inviteFriend failed to delete Ehcache key "+key);
			}
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}catch (Exception e1) {
			e1.printStackTrace(System.out);
			System.exit(-1);
		}
		finally{
			try {
				if(preparedStatement != null)
					preparedStatement.clearParameters();
					//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		return retVal;
	}

	@Override
	public int thawFriendship(int friendid1, int friendid2){
		int retVal = SUCCESS;
		if(friendid1 < 0 || friendid2 < 0)
			return -1;

		String query = "DELETE FROM friendship WHERE (inviterid=? and inviteeid= ?) OR (inviterid=? and inviteeid= ?) and status=2";
		try {
			if((preparedStatement = newCachedStatements.get(UNFRNDFRND_STMT)) == null)
				preparedStatement = createAndCacheStatement(UNFRNDFRND_STMT, query);		
			//preparedStatement = conn.prepareStatement(query);
			preparedStatement.setInt(1, friendid1);
			preparedStatement.setInt(2, friendid2);
			preparedStatement.setInt(3, friendid2);
			preparedStatement.setInt(4, friendid1);

			preparedStatement.executeUpdate();
			
			//Invalidate exisiting list of friends for each member
			String key;
			
			if(isInsertImage)
			{
				key = "lsFrds:"+friendid1;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
	
				key = "lsFrds:"+friendid2;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
	
				//Invalidate the friendcount for each member
				key = "profile"+friendid1;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
	
				key = "profile"+friendid2;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
				
				key = "ownprofile"+friendid1;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
	
				key = "ownprofile"+friendid2;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
			}
			else
			{
				key = "lsFrdsNoImage:"+friendid1;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
	
				key = "lsFrdsNoImage:"+friendid2;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
	
				//Invalidate the friendcount for each member
				key = "profileNoImage"+friendid1;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
	
				key = "profileNoImage"+friendid2;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
				
				key = "ownprofileNoImage"+friendid1;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
	
				key = "ownprofileNoImage"+friendid2;
				if(!CacheEMDelete(key))
				{
					System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
				}
				if (!useTTL)
				{
					if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
					{
						throw new Exception("Error calling WhalinMemcached delete");
					}	
				}
			}
			
			key = "ConfirmedFriendship:"+friendid1;
			if(!CacheEMDelete(key))
			{
				System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
			}
			if (!useTTL)
			{
				if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
				{
					throw new Exception("Error calling WhalinMemcached delete");
				}	
			}
			
			key = "ConfirmedFriendship:"+friendid2;				
			if(!CacheEMDelete(key))
			{
				System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
			}
			if (!useTTL)
			{
				if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
				{
					throw new Exception("Error calling WhalinMemcached delete");
				}	
			}
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}catch (Exception e1) {
			e1.printStackTrace(System.out);
			System.exit(-1);
		}finally{
			try {
				if(preparedStatement != null)
					preparedStatement.clearParameters();
					//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		return retVal;
	}

	@Override
	public int viewTopKResources(int requesterID, int profileOwnerID, int k,
			Vector<HashMap<String, ByteIterator>> result) {
		int retVal = SUCCESS;
		ResultSet rs = null;
		if(profileOwnerID < 0 || requesterID < 0 || k < 0)
			return -1;
		
		String key="TopKRes:"+profileOwnerID;

		try {
			Element elt = CM.get(key);
			if (elt != null){
				Object value = elt.getObjectValue();
				if ( value != null ){
					if (verbose) System.out.println("Hit!  Elts in memory:  "+CM.getMemoryStoreSize()+", Elts in DiskStore "+CM.getDiskStoreSize());
					Vector<HashMap<String, byte[]>> CMEntry = ( Vector<HashMap<String, byte[]>> ) value;
					for (int i=0; i < CMEntry.size(); i++){
						HashMap<String, ByteIterator> resElt = new HashMap<String, ByteIterator>();
						HashMap<String, byte[]> CacheElt = CMEntry.elementAt(i);
						for (Map.Entry<String, byte[]> entry : CacheElt.entrySet()) { 
							if (verbose) System.out.println("key = "+entry.getKey()+", value = "+new String(entry.getValue()));

							if ( ! entry.getKey().equalsIgnoreCase("tpic") )
								resElt.put(entry.getKey(), new ObjectByteIterator(entry.getValue()));
						}
						result.add(resElt);
					}
					return retVal;
				}
			} 
			else
			{ 
				byte[] payload = common.CacheUtilities.CacheEMGet(this.cacheclient, key, this.compressPayload);
				if (payload != null && CacheUtilities.unMarshallVectorOfHashMaps(payload,result)){
					if (verbose) System.out.println("... Cache Hit!");
					//cacheclient.shutdown();
					return retVal;
				}
			
				else if (verbose) System.out.println("... Query DB.");
			}
		} catch (Exception e1) {
			e1.printStackTrace(System.out);
			System.exit(-1);
		} 
		
		Vector<HashMap<String, byte[]>> ResList = new Vector< HashMap<String, byte[]> >();
		HashMap<String, byte[]> OneRes = new HashMap<String, byte[]>();

		String query = "SELECT * FROM resources WHERE walluserid = ? AND rownum <? ORDER BY rid desc";
		try {
			if((preparedStatement = newCachedStatements.get(GETTOPRES_STMT)) == null)
				preparedStatement = createAndCacheStatement(GETTOPRES_STMT, query);		
		
			//preparedStatement = conn.prepareStatement(query);
			preparedStatement.setInt(1, profileOwnerID);
			preparedStatement.setInt(2, (k+1));
			rs = preparedStatement.executeQuery();
			while (rs.next()){
				
				HashMap<String, ByteIterator> values = new HashMap<String, ByteIterator>();
				OneRes = new HashMap<String, byte[]>();
				
				//get the number of columns and their names
				ResultSetMetaData md = rs.getMetaData();
				int col = md.getColumnCount();
				for (int i = 1; i <= col; i++){
					String col_name = md.getColumnName(i);
					String value = rs.getString(col_name);
					if(col_name.equalsIgnoreCase("rid"))
						col_name = "rid";
					else if(col_name.equalsIgnoreCase("walluserid"))
						col_name = "walluserid";
					OneRes.put(col_name, value.getBytes());
					values.put(col_name, new ObjectByteIterator(value.getBytes()));
				}
				ResList.add(OneRes);
				result.add(values);
			}
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}finally{
			try {
				if (rs != null)
					rs.close();
				if(preparedStatement != null)
					preparedStatement.clearParameters();
					//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		
		//Insert the data structure in the cache.
		if (CM != null && ResList != null){
			if (verbose) System.out.println("Popualte the cache with FrdList.");
			CM.put(new Element(key, ResList));
		}

		if (retVal == SUCCESS){
			//serialize the result hashmap and insert it in the cache for future use
			byte[] payload = CacheUtilities.SerializeVectorOfHashMaps(result);
			try {
				if(!common.CacheUtilities.CacheEMSet(this.cacheclient, key, payload, this.useTTL, this.TTLvalue, this.compressPayload))
				{
					throw new Exception("Error calling WhalinMemcached set");
				}
				//cacheclient.shutdown();
			} catch (Exception e1) {
				System.out.println("Error in ApplicationCacheClient, getListOfFriends failed to insert the key-value pair in the cache.");
				e1.printStackTrace(System.out);
				retVal = -2;
			}
		}
		
		return retVal;		
	}

	public int getCreatedResources(int resourceCreatorID, Vector<HashMap<String, ByteIterator>> result) {
		int retVal = SUCCESS;
		ResultSet rs = null;
		Statement st = null;
		if(resourceCreatorID < 0)
			return -1;

		String query = "SELECT * FROM resources WHERE creatorid = "+resourceCreatorID;
		try {
			st = conn.createStatement();
			rs = st.executeQuery(query);
			while (rs.next()){
				HashMap<String, ByteIterator> values = new HashMap<String, ByteIterator>();
				//get the number of columns and their names
				ResultSetMetaData md = rs.getMetaData();
				int col = md.getColumnCount();
				for (int i = 1; i <= col; i++){
					String col_name = md.getColumnName(i);
					String value = rs.getString(col_name);
					if(col_name.equalsIgnoreCase("rid"))
						col_name = "rid";
					values.put(col_name, new ObjectByteIterator(value.getBytes()));
				}
				result.add(values);
			}
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}finally{
			try {
				if (rs != null)
					rs.close();
				if(st != null)
					st.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}

		return retVal;		
	}

	@Override
	public int viewCommentOnResource(int requesterID, int profileOwnerID,
			int resourceID, Vector<HashMap<String, ByteIterator>> result) {
		int retVal = SUCCESS;
		ResultSet rs = null;
		if(profileOwnerID < 0 || requesterID < 0 || resourceID < 0)
			return -1;
		
		String key="ResCmts:"+resourceID;
		String query="";
		Vector<HashMap<String, byte[]>> CmtList = null;
		HashMap<String, byte[]> OneCmt = null;
		
		try {
			Element elt = CM.get(key);
			if (elt != null){
				Object value = elt.getObjectValue();
				if ( value != null ){
					if (verbose) System.out.println("Hit!  Elts in memory:  "+CM.getMemoryStoreSize()+", Elts in DiskStore "+CM.getDiskStoreSize());
					Vector<HashMap<String, byte[]>> CMEntry = ( Vector<HashMap<String, byte[]>> ) value;
					for (int i=0; i < CMEntry.size(); i++){
						HashMap<String, ByteIterator> resElt = new HashMap<String, ByteIterator>();
						HashMap<String, byte[]> CacheElt = CMEntry.elementAt(i);
						for (Map.Entry<String, byte[]> entry : CacheElt.entrySet()) { 
							if (verbose) System.out.println("key = "+entry.getKey()+", value = "+new String(entry.getValue()));

							if ( ! entry.getKey().equalsIgnoreCase("tpic") )
								resElt.put(entry.getKey(), new ObjectByteIterator(entry.getValue()));
						}
						result.add(resElt);
					}
					return retVal;
				}
			}
			else
			{
				byte[] payload = common.CacheUtilities.CacheEMGet(this.cacheclient, key, this.compressPayload);
				if (payload != null && CacheUtilities.unMarshallVectorOfHashMaps(payload,result)){
					if (verbose) System.out.println("... Cache Hit!");
					//cacheclient.shutdown();
					return retVal;
				}
				else if (verbose) System.out.println("... Query DB.");
			}
		} catch (Exception e1) {
			e1.printStackTrace(System.out);
			System.exit(-1);
		} 
		
		CmtList = new Vector< HashMap<String, byte[]> >();
		
		
		//get comment cnt
		try {	
			query = "SELECT * FROM manipulation WHERE rid = ?";		
			if((preparedStatement = newCachedStatements.get(GETRESCMT_STMT)) == null)
				preparedStatement = createAndCacheStatement(GETRESCMT_STMT, query);		
			//preparedStatement = conn.prepareStatement(query);
			preparedStatement.setInt(1, resourceID);
			rs = preparedStatement.executeQuery();
			while (rs.next()){
				
				HashMap<String, ByteIterator> values = new HashMap<String, ByteIterator>();
				OneCmt = new HashMap<String, byte[]>();
				
				//get the number of columns and their names
				ResultSetMetaData md = rs.getMetaData();
				int col = md.getColumnCount();
				for (int i = 1; i <= col; i++){
					String col_name = md.getColumnName(i);
					String value = rs.getString(col_name);
					values.put(col_name, new ObjectByteIterator(value.getBytes()));
				}
				CmtList.add(OneCmt);
				result.add(values);
			}
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}finally{
			try {
				if (rs != null)
					rs.close();
				if(preparedStatement != null)
					preparedStatement.clearParameters();
					//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}
		
		//Insert the data structure in the cache.
		if (CM != null && CmtList != null){
			if (verbose) System.out.println("Popualte the cache with CmtList.");
			CM.put(new Element(key, CmtList));
		}
		
		if (retVal == SUCCESS){
			//serialize the result hashmap and insert it in the cache for future use
			byte[] payload = CacheUtilities.SerializeVectorOfHashMaps(result);
			try {
				if(!common.CacheUtilities.CacheEMSet(this.cacheclient, key, payload, this.useTTL, this.TTLvalue, this.compressPayload))
				{
					throw new Exception("Error calling WhalinMemcached set");
				}
				//cacheclient.shutdown();
			} catch (Exception e1) {
				System.out.println("Error in ApplicationCacheClient, getListOfFriends failed to insert the key-value pair in the cache.");
				e1.printStackTrace(System.out);
				retVal = -2;
			}
		}

		return retVal;		
	}

	@Override
	public int postCommentOnResource(int commentCreatorID, int profileOwnerID,
			int resourceID, HashMap<String,ByteIterator> commentValues) {
		int retVal = SUCCESS;

		if(profileOwnerID < 0 || commentCreatorID < 0 || resourceID < 0)
			return -1;

		String query = "INSERT INTO manipulation(mid, creatorid, rid, modifierid, timestamp, type, content) VALUES (?,?, ?,?, ?, ?)";
		try {
			if((preparedStatement = newCachedStatements.get(POSTCMT_STMT)) == null)
				preparedStatement = createAndCacheStatement(POSTCMT_STMT, query);		
			//preparedStatement = conn.prepareStatement(query);
			preparedStatement.setInt(1, Integer.parseInt(commentValues.get("mid").toString()));
			preparedStatement.setInt(2, profileOwnerID);
			preparedStatement.setInt(3, resourceID);
			preparedStatement.setInt(4,commentCreatorID);
			preparedStatement.setString(5,commentValues.get("timestamp").toString());
			preparedStatement.setString(6,commentValues.get("type").toString());
			preparedStatement.setString(7,commentValues.get("content").toString());
			preparedStatement.executeUpdate();
			
			String key = "ResCmts:"+resourceID;
			//MemcachedClient cacheclient = new MemcachedClient(AddrUtil.getAddresses(cache_hostname + ":" + cache_port));
			if(!CacheEMDelete(key))
			{
				System.out.println("Error, in postCommentOnResource failed to delete key "+key);
			}
			//cacheclient.shutdown();
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		}finally{
			try {
				if(preparedStatement != null)
					preparedStatement.clearParameters();
					//preparedStatement.close();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}

		return retVal;		
	}

	@Override
	public int delCommentOnResource(int resourceCreatorID, int resourceID,
			int manipulationID) {
		
		int retVal = SUCCESS;

		if(resourceCreatorID < 0 || resourceID < 0 || manipulationID < 0)
			return -1;

		String query = "DELETE FROM manipulation WHERE mid=? AND rid=?";
		
		try 
		{
			if((preparedStatement = newCachedStatements.get(DELCMT_STMT)) == null)
				preparedStatement = createAndCacheStatement(DELCMT_STMT, query);	
			preparedStatement.setInt(1, manipulationID);
			preparedStatement.setInt(2, resourceID);
			preparedStatement.executeUpdate();
			
			String key = "ResCmts:"+resourceID;
			if(!CacheEMDelete(key))
			{
				System.out.println("Error:  thawFriendship failed to delete Ehcache key "+key);
			}
			if (!useTTL)
			{
				if(!common.CacheUtilities.CacheEMDelete(this.cacheclient, key))
				{
					throw new Exception("Error calling WhalinMemcached delete");
				}
			}
			
		}catch(SQLException sx){
			retVal = -2;
			sx.printStackTrace(System.out);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}finally{
			try {
				if(preparedStatement != null)
					preparedStatement.clearParameters();
			} catch (SQLException e) {
				retVal = -2;
				e.printStackTrace(System.out);
			}
		}

		return retVal;	
	}
	
	@Override
	//init phase query not cached
	public HashMap<String, String> getInitialStats() {
		HashMap<String, String> stats = new HashMap<String, String>();
		Statement st = null;
		ResultSet rs = null;
		String query = "";
		try {
			st = conn.createStatement();
			//get user count
			query = "SELECT count(*) from users";
			rs = st.executeQuery(query);
			if(rs.next()){
				stats.put("usercount",rs.getString(1));
			}else
				stats.put("usercount","0"); //sth is wrong - schema is missing
			if(rs != null ) rs.close();
			//get user offset
			query = "SELECT min(userid) from users";
			rs = st.executeQuery(query);
			String offset = "0";
			if(rs.next()){
				offset = rs.getString(1);
			}
			//get resources per user
			query = "SELECT count(*) from resources where creatorid="+Integer.parseInt(offset);
			rs = st.executeQuery(query);
			if(rs.next()){
				stats.put("resourcesperuser",rs.getString(1));
			}else{
				stats.put("resourcesperuser","0");
			}
			if(rs != null) rs.close();	
			//get number of friends per user
			query = "select count(*) from friendship where (inviterid="+Integer.parseInt(offset) +" OR inviteeid="+Integer.parseInt(offset) +") AND status=2" ;
			rs = st.executeQuery(query);
			if(rs.next()){
				stats.put("avgfriendsperuser",rs.getString(1));
			}else
				stats.put("avgfriendsperuser","0");
			if(rs != null) rs.close();
			query = "select count(*) from friendship where (inviteeid="+Integer.parseInt(offset) +") AND status=1" ;
			rs = st.executeQuery(query);
			if(rs.next()){
				stats.put("avgpendingperuser",rs.getString(1));
			}else
				stats.put("avgpendingperuser","0");
			

		}catch(SQLException sx){
			sx.printStackTrace(System.out);
		}finally{
			try {
				if(rs != null)
					rs.close();
				if(st != null)
					st.close();
			} catch (SQLException e) {
				e.printStackTrace(System.out);
			}

		}
		return stats;
	}

	//init phase query not cached
	public int queryPendingFriendshipIds(int inviteeid, Vector<Integer> pendingIds){
		int retVal = SUCCESS;
		Statement st = null;
		ResultSet rs = null;
		String query = "";
		if(inviteeid < 0)
			retVal = -1;
		try {
			st = conn.createStatement();
			query = "SELECT inviterid from friendship where inviteeid='"+inviteeid+"' and status='1'";
			rs = st.executeQuery(query);
			while(rs.next()){
				pendingIds.add(rs.getInt(1));
			}	
		}catch(SQLException sx){
			sx.printStackTrace(System.out);
			retVal = -2;
		}finally{
			try {
				if(rs != null)
					rs.close();
				if(st != null)
					st.close();
			} catch (SQLException e) {
				e.printStackTrace(System.out);
				retVal = -2;
			}
		}
		return retVal;
	}

	//init phase query not cached
	public int queryConfirmedFriendshipIds(int profileId, Vector<Integer> confirmedIds){
		int retVal = SUCCESS;
		Statement st = null;
		ResultSet rs = null;
		String query = "";
		if(profileId < 0)
			retVal = -1;
		try {
			st = conn.createStatement();
			query = "SELECT inviterid, inviteeid from friendship where (inviteeid="+profileId+" OR inviterid="+profileId+") and status='2'";
			rs = st.executeQuery(query);
			while(rs.next()){
				if(rs.getInt(1) != profileId)
					confirmedIds.add(rs.getInt(1));
				else
					confirmedIds.add(rs.getInt(2));
			}	
		}catch(SQLException sx){
			sx.printStackTrace(System.out);
			retVal = -2;
		}finally{
			try {
				if(rs != null)
					rs.close();
				if(st != null)
					st.close();
			} catch (SQLException e) {
				e.printStackTrace(System.out);
				retVal = -2;
			}
		}
		return retVal;

	}

	
	
	
	@Override
	public void createSchema(Properties props){
		RdbmsUtilities.createSchema(props, conn);
	}
    @Override
	public void buildIndexes(Properties props){
		RdbmsUtilities.buildIndexes(props, conn);
	}

	public static void dropSequence(Statement st, String seqName) {
		try {
			st.executeUpdate("drop sequence " + seqName);
		} catch (SQLException e) {
		}
	}

	public static void dropIndex(Statement st, String idxName) {
		try {
			st.executeUpdate("drop index " + idxName);
		} catch (SQLException e) {
		}
	}

	public static void dropTable(Statement st, String tableName) {
		try {
			st.executeUpdate("drop table " + tableName);
		} catch (SQLException e) {
		}
	}
}