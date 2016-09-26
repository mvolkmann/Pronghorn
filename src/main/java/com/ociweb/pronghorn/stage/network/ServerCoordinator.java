package com.ociweb.pronghorn.stage.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.util.MemberHolder;
import com.ociweb.pronghorn.util.ServiceObjectHolder;
import com.ociweb.pronghorn.util.ServiceObjectValidator;

public class ServerCoordinator {

    private final ServiceObjectHolder<SocketChannel>[] socketHolder;
    private final Selector[]                           selectors;
    private final MemberHolder[]                       subscriptions;
    private final int[][]                              upgradePipeLookup;
    private final ConnectionContext[][]                connectionContext; //NOTE: ObjectArrays would work very well here!!
            
    public final int                                  channelBits;
    public final int                                  channelBitsSize;
    public final int                                  channelBitsMask;

    private final int port;
    private final InetSocketAddress                    address; 
    
    public ServerCoordinator(int socketGroups, int port) {
        this.socketHolder      = new ServiceObjectHolder[socketGroups];    
        this.selectors         = new Selector[socketGroups];
        this.subscriptions     = new MemberHolder[socketGroups];
        this.port              = port;
        this.upgradePipeLookup        = new int[socketGroups][];
        this.connectionContext = new ConnectionContext[socketGroups][];
        this.channelBits       = 12; //max of 4096 open connections per stage
        this.channelBitsSize   = 1<<channelBits;
        this.channelBitsMask   = channelBitsSize-1;
        this.address           = new InetSocketAddress("127.0.0.1",port);
        
    }
    
    public InetSocketAddress getAddress() {
        return address;
    }

    
    public static ServiceObjectHolder<SocketChannel> newSocketChannelHolder(ServerCoordinator that, int idx) {
        that.connectionContext[idx] = new ConnectionContext[that.channelBitsSize];
        //must also create these long lived instances, this would be a good use case for StructuredArray and ObjectLayout
        int i = that.channelBitsSize;
        while (--i >= 0) {
            that.connectionContext[idx][i] = new ConnectionContext();
        }
        
        that.upgradePipeLookup[idx] = new int[that.channelBitsSize];
        return that.socketHolder[idx] = new ServiceObjectHolder(that.channelBits, SocketChannel.class, new SocketValidator(), false/*Do not grow*/);
        
    }
    
    public static ServiceObjectHolder<SocketChannel> getSocketChannelHolder(ServerCoordinator that, int idx) {
        while (null==that.socketHolder[idx]) {//TODO: may need to find more elegant way to do this but this will probably do just fine.
            Thread.yield();//we have a race that happens on graph building so is may have to wait here.
        }
        return that.socketHolder[idx];
    }
    
    public MemberHolder newMemberHolder(int idx) {
        return subscriptions[idx] = new MemberHolder(100);
    }
    
    public static class SocketValidator implements ServiceObjectValidator<SocketChannel> {

        @Override
        public boolean isValid(SocketChannel serviceObject) {            
            return serviceObject.isConnectionPending() || 
                   serviceObject.isConnected();
        }

        @Override
        public void dispose(SocketChannel t) {            
            try {
                if (t.isOpen()) {
                    t.close();
                }
            } catch (IOException e) {
                //ignore since we are wanting this object to get garbage collected anyway.
            }            
        }
        
    }


    
    public static int[] getUpgradedPipeLookupArray(ServerCoordinator that, int idx) {
        return that.upgradePipeLookup[idx];
    }
    public static void setTargetUpgradePipeIdx(ServerCoordinator coordinator, int groupId, long channelId, int idxValue) {
        coordinator.upgradePipeLookup[groupId][(int)(coordinator.channelBitsMask & channelId)] = idxValue;
    }
    public static int getTargetUpgradePipeIdx(ServerCoordinator coordinator, int groupId, long channelId) {
        return coordinator.upgradePipeLookup[groupId][(int)(coordinator.channelBitsMask & channelId)];
    }
    
    public static Selector getSelector(ServerCoordinator that, int idx) {
        return that.selectors[idx];
    }
    
    public static ConnectionContext selectorKeyContext(ServerCoordinator that, int idx, long channelId) {
        ConnectionContext context = that.connectionContext[idx][(int)(that.channelBitsMask & channelId)];
        context.channelId = channelId;        
        return context;
    }

    static int scanForOptimalPipe(ServerCoordinator that, int minValue, int minIdx) {
        int i = that.socketHolder.length;
        ServiceObjectHolder<SocketChannel>[] localSocketHolder=that.socketHolder;
        while (--i>=0) {
             ServiceObjectHolder<SocketChannel> holder = localSocketHolder[i];                         
             int openConnections = (int)(ServiceObjectHolder.getSequenceCount(holder) - ServiceObjectHolder.getRemovalCount(holder));
             if (openConnections<minValue /*&& Pipe.hasRoomForWrite(localOutputs[i])*/ ) {
                 minValue = openConnections;
                 minIdx = i;
             } 
          }
          return minIdx;
    }

    public void registerSelector(int pipeIdx, Selector selector) {
        selectors[pipeIdx] = selector;
    }

}
