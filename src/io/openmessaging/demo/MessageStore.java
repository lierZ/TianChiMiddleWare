package io.openmessaging.demo;

import io.openmessaging.Message;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


public class MessageStore {

	private final String EXTNAME=".queue";
	private final int LOOPINTERVAL=1000;
	private final int MAXMESSAGELENGTH=256*1024;
	private final String OFFSETFILE="MsgOffsets";
	private static String STORE_PATH=null;
    
     
    private static final HashMap<String,FileChannel> fileHandlers=new HashMap<>();
    
    private static final HashMap<String , Integer> bucketIndex=new HashMap<>();
    private static final MessageStore INSTANCE = new MessageStore();
    private Thread loopSaveHandler=null;
    private Thread indexSaveHandler=null;							
    public MessageStore() {
		// TODO Auto-generated constructor stub
    	//TODO 从文件中读queueOffset
    	FileChannel f = fileHandlers.get(OFFSETFILE);
    	try{
			if (f == null) {
				//TODO
				File file = new File(STORE_PATH + File.pathSeparator + OFFSETFILE + EXTNAME);
				if (file.exists()) {
//					System.out.println(file.getAbsolutePath());
					f = new RandomAccessFile(file, "rw").getChannel();
					fileHandlers.put(OFFSETFILE, f);
					ByteBuffer buff=ByteBuffer.allocate(MAXMESSAGELENGTH);
					f.read(buff);
					queueOffsets=(HashMap) new ObjectInputStream(new ByteArrayInputStream(buff.array())).readObject();
				}	
			}
			
    	}catch (Exception e){
    		e.printStackTrace();
    	}
    	loopSaveHandler=new Thread(new LoopSave());
    	//loopSaveHandler.start();
    	indexSaveHandler=new Thread(new IndexSave());
    	//indexSaveHandler.start();
	}

    public static MessageStore getInstance() {
        return INSTANCE;
    }
    
    public void setSaveFilePathIfNot(String path){
    	if(STORE_PATH==null){
    		STORE_PATH=path;
    	//	loopSaveHandler.start();
    		indexSaveHandler.start();
    	}
    }
    
    private Map<String, ArrayList<Message>> messageBuckets = new ConcurrentHashMap<>();

    private Map<String, HashMap<String, Integer>> queueOffsets = new HashMap<>();
    public synchronized void putMessage(String bucket, Message message) {
    	FileChannel f = fileHandlers.get(bucket);
		try {
			if (f == null) {
				//TODO
				File file = new File(STORE_PATH + File.pathSeparator + bucket + EXTNAME);
				if (!file.exists()) {
					file.createNewFile();
				}
				f = new RandomAccessFile(file, "rw").getChannel();
				fileHandlers.put(bucket, f);
			}
			ByteArrayOutputStream out=new ByteArrayOutputStream();
			ObjectOutputStream oos=new ObjectOutputStream(out);
			oos.writeObject(message);
			byte[] objectBuf=out.toByteArray();
//				System.out.println(objectBuf.length);
			ByteBuffer buf=ByteBuffer.allocate(objectBuf.length+4);
			buf.putInt(objectBuf.length);
			buf.put(objectBuf);
			buf.flip();
			synchronized (f) {
				MappedByteBuffer mmb=f.map(FileChannel.MapMode.READ_WRITE, f.size(),objectBuf.length+4 );
				mmb.put(buf);
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

    }

    public synchronized Message pullMessage(String queue, String bucket) {
        ArrayList<Message> bucketList = messageBuckets.get(bucket);
        FileChannel f = fileHandlers.get(bucket);
        try{
	        if (f == null) {//file not exist return null
	        	File file = new File(STORE_PATH + File.pathSeparator + bucket + EXTNAME);
				if (!file.exists()) {
					return null;
				}
				f = new RandomAccessFile(file, "rw").getChannel();
				fileHandlers.put(bucket, f);
	        }
	        HashMap<String, Integer> offsetMap = queueOffsets.get(queue);
	        if (offsetMap == null) {
	            offsetMap = new HashMap<>();
	            queueOffsets.put(queue, offsetMap);
	        }
	        int[] offset =new int[1]; 
	        offset[0]=offsetMap.getOrDefault(bucket, 0);
	        //from file
	        if (offset[0] >= f.size()) {
	            return null;
	        }
	       
	        Message message = null;
	        synchronized(f){
	        	message=getMessageFromFile(f, offset);
	        }
	        if(message==null)
	        	return null;
	        
	        offsetMap.put(bucket, offset[0]);
	        return message;
        }
        catch(Exception e){
        	e.printStackTrace();
        }
        return null;
    }
    private DefaultBytesMessage getMessageFromFile(FileChannel f,int[] offset){
    	try {
    		MappedByteBuffer mmb=f.map(MapMode.READ_ONLY, offset[0], 4);
    		int length=mmb.getInt();
    		mmb=f.map(MapMode.READ_ONLY, offset[0]+4, length);
    		byte[] buf=new byte[length];
    		mmb.get(buf);
    		ByteArrayInputStream in=new ByteArrayInputStream(buf);
    		ObjectInputStream ois=new ObjectInputStream(in);
    		DefaultBytesMessage result=(DefaultBytesMessage) ois.readObject();
    		offset[0]+=length+4;
			return result;
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	return null;
    }
    
    private class IndexSave implements Runnable{
	    @Override
		public void run() {
			// TODO Auto-generated method stub
	    	ByteBuffer buf=ByteBuffer.allocate(MAXMESSAGELENGTH);
			try {
				while (true) {
					Thread.sleep(LOOPINTERVAL);
					FileChannel f = fileHandlers.get(OFFSETFILE);
					if (f == null) {
						//TODO
						File file = new File(STORE_PATH + File.pathSeparator + OFFSETFILE + EXTNAME);
						if (!file.exists()) {
							file.createNewFile();
						}
						f = new RandomAccessFile(file, "rw").getChannel();
						fileHandlers.put(OFFSETFILE, f);
					}
					buf.clear();
					ByteArrayOutputStream out=new ByteArrayOutputStream();
					ObjectOutputStream oos=new ObjectOutputStream(out);
					oos.writeObject(queueOffsets);
					byte[] objectBuf=out.toByteArray();
					buf.put(objectBuf);
					buf.flip();
					synchronized (f) {
						f.position(0);
						f.write(buf);
						f.force(true);
					} 
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
    }
   
    private class LoopSave implements Runnable{
    	@Override
    	public void run() {
    		while (true) {
				// TODO Auto-generated method stub
				Set<String> keys = messageBuckets.keySet();
				for (String key : keys) {
					ArrayList<Message> bucketList = messageBuckets.get(key);
					if (bucketList == null) {
						continue;
					}
					int offset = bucketIndex.getOrDefault(key, 0);
					if (offset >= bucketList.size()) {
						continue;
					}
					Message message = bucketList.get(offset);
					if(message==null){
						continue;
					}
//					System.out.println(message);
					bucketIndex.put(key, ++offset);
					//写文件
					FileChannel f = fileHandlers.get(key);
					try {
						if (f == null) {
							//TODO
							File file = new File(STORE_PATH + File.pathSeparator + key + EXTNAME);
							if (!file.exists()) {
								file.createNewFile();
							}
							f = new RandomAccessFile(file, "rw").getChannel();
							fileHandlers.put(key, f);
						}
						ByteArrayOutputStream out=new ByteArrayOutputStream();
						ObjectOutputStream oos=new ObjectOutputStream(out);
						oos.writeObject(message);
						byte[] objectBuf=out.toByteArray();
		//				System.out.println(objectBuf.length);
						ByteBuffer buf=ByteBuffer.allocate(objectBuf.length+4);
						buf.putInt(objectBuf.length);
						buf.put(objectBuf);
						buf.flip();
						synchronized (f) {
							f.position(f.size());
							int len=f.write(buf);
							f.force(true);
						}
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}

				} 
			}
	   }
    }
}
