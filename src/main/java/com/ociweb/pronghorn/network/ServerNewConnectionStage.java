package com.ociweb.pronghorn.network;

import com.ociweb.pronghorn.network.schema.ServerConnectionSchema;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;
import com.ociweb.pronghorn.util.ServiceObjectHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * General base class for server construction.
 * Server should minimize garbage but unlike client may not be possible to remove it.
 * 
 * No protocol specifics are found in this class only socket usage logic
 * 
 * @author Nathan Tippy
 *
 */
public class ServerNewConnectionStage extends PronghornStage{
        
    private static final int CONNECTION_TIMEOUT = 7_000; 

	private static final Logger logger = LoggerFactory.getLogger(ServerNewConnectionStage.class);
    
    private Selector selector;
    private int selectionKeysAllowedToWait = 0;//NOTE: should test what happens when we make this bigger.
    private ServerSocketChannel server;
    
    static final int connectMessageSize = ServerConnectionSchema.FROM.fragScriptSize[ServerConnectionSchema.MSG_SERVERCONNECTION_100];
    private ServerCoordinator coordinator;
    private Pipe<ServerConnectionSchema> newClientConnections;
    private final String label;
    
	public static ServerNewConnectionStage newIntance(GraphManager graphManager, ServerCoordinator coordinator, Pipe<ServerConnectionSchema> newClientConnections, boolean isTLS) {
		return new ServerNewConnectionStage(graphManager,coordinator,newClientConnections);
	}
	
    public ServerNewConnectionStage(GraphManager graphManager, ServerCoordinator coordinator, Pipe<ServerConnectionSchema> newClientConnections) {
        super(graphManager, NONE, newClientConnections);
        this.coordinator = coordinator;
        
        this.label = coordinator.host()+":"+coordinator.port();
        
        this.newClientConnections = newClientConnections;
        
        GraphManager.addNota(graphManager, GraphManager.DOT_BACKGROUND, "lemonchiffon3", this);
    }
    
	public static ServerNewConnectionStage newIntance(GraphManager graphManager, ServerCoordinator coordinator, boolean isTLS) {
		return new ServerNewConnectionStage(graphManager,coordinator);
	}
	
    public ServerNewConnectionStage(GraphManager graphManager, ServerCoordinator coordinator) {
        super(graphManager, NONE, NONE);
        this.coordinator = coordinator;
        
        this.label = coordinator.host()+":"+coordinator.port();
        
        this.newClientConnections = null;
        GraphManager.addNota(graphManager, GraphManager.DOT_BACKGROUND, "lemonchiffon3", this);
		
    }
    
    @Override
    public String toString() {
    	String root = super.toString();
    	return root+"\n"+label+"\n";
    }
    
    @Override
    public void startup() {

    	/////////////////////////////////////////////////////////////////////////////////////
    	//The trust manager MUST be established before any TLS connection work begins
    	//If this is not done there can be race conditions as to which certs are trusted...
    	if (coordinator.isTLS) {
    		coordinator.engineFactory.initTLSService();
    	}
    	logger.trace("init of Server TLS called {}",coordinator.isTLS);
    	/////////////////////////////////////////////////////////////////////////////////////
    	

    	SocketAddress endPoint = null;

    	try {
    		
            //logger.info("startup of new server");
    		//channel is not used until connected
    		//once channel is closed it can not be opened and a new one must be created.
    		server = ServerSocketChannel.open();
    		
    		//to ensure that this port can be re-used quickly for testing and other reasons
    		server.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
    		server.socket().setPerformancePreferences(1, 2, 0);
    		server.socket().setSoTimeout(0);
    		    		
    		endPoint = bindAddressPort(coordinator.host(), coordinator.port());
            
            ServerSocketChannel channel = (ServerSocketChannel)server.configureBlocking(false);

            //this stage accepts connections as fast as possible
            selector = Selector.open();
            
            channel.register(selector, SelectionKey.OP_ACCEPT); 
            
            //trim of local domain name when present.
            String host = endPoint.toString();

            int hidx = host.indexOf('/');
            if (hidx==0) {
            	host = host.substring(hidx+1,host.length());
            } else if (hidx>0) {
            	int colidx = host.indexOf(':');
            	if (colidx<0) {
            		host = host.substring(0,hidx);
            	} else {
            		host = host.substring(0,hidx)+host.substring(colidx,host.length());
            	}
            }
            
            //ensure reporting is done together
            synchronized(logger) {
            	System.out.println();
	            System.out.println(coordinator.serviceName()+" is now ready on http"+(coordinator.isTLS?"s":"")+"://"+host+"/"+coordinator.defaultPath());
	            System.out.println(coordinator.serviceName()+" max connections: "+coordinator.channelBitsSize);
	            System.out.println(coordinator.serviceName()+" max concurrent inputs: "+coordinator.maxConcurrentInputs);
	            System.out.println(coordinator.serviceName()+" concurrent tracks: "+coordinator.moduleParallelism());
	            System.out.println(coordinator.serviceName()+" max concurrent outputs: "+coordinator.maxConcurrentOutputs);
	            System.out.println();
            }
            
        } catch (SocketException se) {
         
	    	if (se.getMessage().contains("Permission denied")) {
	    		logger.warn("\nUnable to open {} due to {}",endPoint,se.getMessage());
	    		coordinator.shutdown();
	    		return;
	    	} else {
	        	if (se.getMessage().contains("already in use")) {
	                logger.warn("Already in use: {}",endPoint,se.getMessage());
	                coordinator.shutdown();
	                return;
	            }
	    	}
            throw new RuntimeException(se);
        } catch (IOException e) {
           if (e.getMessage().contains("Unresolved address")) {
        	   logger.warn("\nUnresolved host address  http"+(coordinator.isTLS?"s":""));
        	   coordinator.shutdown();
               return;
           }
        	
           throw new RuntimeException(e);
           
        }
        
    }

	private SocketAddress bindAddressPort(String host, int port) throws IOException, BindException {
		
		InetSocketAddress endPoint = null;
		
		long timeout = System.currentTimeMillis()+CONNECTION_TIMEOUT;
		boolean notConnected = true;
		int printWarningCountdown = 20;
		do {
		    try{
		    	if (null == endPoint) {
		    		endPoint = new InetSocketAddress(host, port);		
		    		logger.info("bind to {} ",endPoint);
		    	}
		    	server.socket().bind(endPoint);
		    	notConnected = false;
		    } catch (BindException se) {
		    	if (System.currentTimeMillis() > timeout) {
		    		logger.warn("Timeout attempting to open open {}",endPoint,se.getMessage());
		    		coordinator.shutdown();
		    		throw se;
		    	} else {
		    		//small pause before retry
		    		try {
		    			if (0 == printWarningCountdown--) {
		    				logger.warn("Unable to open {} this port appears to already be in use.",endPoint);
		    			}
						Thread.sleep( printWarningCountdown>=0 ? 5 : 20);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
		    	}
		    }
		} while (notConnected);
		return endPoint;
	}

    @Override
    public void run() {
  
    	//long now = System.nanoTime();
    	
        try {//selector may be null if shutdown was called on startup.
           if (null!=selector && selector.selectNow() > selectionKeysAllowedToWait) {
                //we know that there is an interesting (non zero positive) number of keys waiting.
                                
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                
                  SelectionKey key = keyIterator.next();
                  keyIterator.remove();
                  int readyOps = key.readyOps();
                                    
                  if (0 != (SelectionKey.OP_ACCEPT & readyOps)) {
                     
                	  //ServerCoordinator.acceptConnectionStart = now;
                	  
                      if (null!=newClientConnections && !Pipe.hasRoomForWrite(newClientConnections, ServerNewConnectionStage.connectMessageSize)) {
                          return;
                      }

                      ServiceObjectHolder<ServerConnection> holder = ServerCoordinator.getSocketChannelHolder(coordinator);
                                            
                      final long channelId = holder.lookupInsertPosition();
                      if (channelId<0) {
                    	  return;//try again later if the client is still waiting.
                      }
                      
                      int targetPipeIdx = 0;//NOTE: this will be needed for rolling out new sites and features atomicly
                                            
                      SocketChannel channel = server.accept();
                      
                      try {                          
                          channel.configureBlocking(false);
                          
                          //TCP_NODELAY is requried for HTTP/2 get used to it being on now.
                          channel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);  
                          channel.socket().setPerformancePreferences(0, 2, 1);
                 
                          SSLEngine sslEngine = null;
                          if (coordinator.isTLS) {
							  sslEngine = coordinator.engineFactory.createSSLEngine();//// not needed for server? host, port);
							  sslEngine.setUseClientMode(false); //here just to be complete and clear
							  // sslEngine.setNeedClientAuth(true); //only if the auth is required to have a connection
							  // sslEngine.setWantClientAuth(true); //the auth is optional
							  sslEngine.setNeedClientAuth(coordinator.requireClientAuth); //required for openSSL/boringSSL
							  
							  sslEngine.beginHandshake();
                          }
						  
						  
						  //logger.debug("server new connection attached for new id {} ",channelId);
						  
						  holder.setValue(channelId, new ServerConnection(sslEngine, channel, channelId));
                                                                                                                            
                         // logger.info("register new data to selector for pipe {}",targetPipeIdx);
                          Selector selector2 = ServerCoordinator.getSelector(coordinator);
						  channel.register(selector2, SelectionKey.OP_READ, ServerCoordinator.selectorKeyContext(coordinator, channelId));
    						
						  if (null!=newClientConnections) {
	                          publishNotificationOFNewConnection(targetPipeIdx, channelId);
						  }
						  
                          
                      } catch (IOException e) {
                    	  logger.trace("Unable to accept connection",e);
                      } 

                  } else {
                	  logger.error("should this be run at all?");
                      assert(0 != (SelectionKey.OP_CONNECT & readyOps)) : "only expected connect";
                      ((SocketChannel)key.channel()).finishConnect(); //TODO: if this does not scale we should move it to the IOStage.
                  }
                                   
                }
            }
        } catch (IOException e) {
        	logger.trace("Unable to open new incoming connection",e);
        }
    }

	private void publishNotificationOFNewConnection(int targetPipeIdx, final long channelId) {
		//the pipe selected has already been checked to ensure room for the connect message                      
		  Pipe<ServerConnectionSchema> targetPipe = newClientConnections;
		  
		  int msgSize = Pipe.addMsgIdx(targetPipe, ServerConnectionSchema.MSG_SERVERCONNECTION_100);
		  
		  Pipe.addLongValue(targetPipeIdx,targetPipe);
		  Pipe.addLongValue(channelId, targetPipe);
		  
		  Pipe.confirmLowLevelWrite(targetPipe, msgSize);
		  Pipe.publishWrites(targetPipe);
	}



    
    private static String[] intersection(String[] a, String[] b) {
        return a;
    }

}
