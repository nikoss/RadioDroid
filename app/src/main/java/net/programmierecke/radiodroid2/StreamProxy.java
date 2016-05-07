package net.programmierecke.radiodroid2;

import android.os.AsyncTask;
import android.util.Log;

import net.programmierecke.radiodroid2.data.ShoutcastInfo;
import net.programmierecke.radiodroid2.interfaces.IConnectionReady;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;

public class StreamProxy {
    private IConnectionReady callback;
    private String uri;
    private long connectionBytesTotal = 0;
    private Socket socketProxy;
    private volatile String localAdress = null;

    public StreamProxy(String uri, IConnectionReady callback) {
        this.uri = uri;
        this.callback = callback;

        createProxy();
    }

    private void createProxy() {
        Log.i("ABC","thread started");
        ServerSocket proxyServer = null;
        try {
            proxyServer = new ServerSocket(0, 1, InetAddress.getLocalHost());
            int port = proxyServer.getLocalPort();
            localAdress = String.format(Locale.US,"http://localhost:%d",port);
        } catch (IOException e) {
            Log.e("ABC",""+e);
        }

        if (proxyServer != null) {
            final ServerSocket finalProxyServer = proxyServer;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i("ABC", "waiting..");
                        socketProxy = finalProxyServer.accept();
                        finalProxyServer.close();

                        doConnectToStream();

                        Log.i("ABC", "juhu");
                    } catch (IOException e) {
                        Log.e("ABC", "" + e);
                    }
                }
            }).start();

            while (localAdress == null) {
                try {
                    Log.i("ABC", "starting serversock...");
                    Thread.sleep(100);
                } catch (Exception e) {
                }
            }
        }
    }

    private void doConnectToStream() {
        try {
            // connect to stream
            URLConnection connection = new URL(uri).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Icy-MetaData", "1");
            connection.connect();

            InputStream in = connection.getInputStream();

            // send ok message to local mediaplayer
            OutputStream out = socketProxy.getOutputStream();
            out.write(("HTTP/1.0 200 OK\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Content-Type: " + connection.getContentType() +
                    "\r\n\r\n").getBytes("utf-8"));

            // try to get shoutcast information from stream connection
            ShoutcastInfo info = ShoutcastInfo.Decode(connection);

            int bytesUntilMetaData = 0;
            boolean readMetaData = false;
            boolean filterOutMetaData = false;

            if (info != null) {
                callback.foundShoutcastStream(info);
                bytesUntilMetaData = info.metadataOffset;
                filterOutMetaData = true;
            }

            byte buf[] = new byte[16384];
            byte bufMetadata[] = new byte[256 * 16];
            int readBytesBuffer = 0;
            int readBytesBufferMetadata = 0;

            while (true) {
                int readBytes = 0;
                if (!filterOutMetaData || (bytesUntilMetaData > 0))
                {
                    int bytesToRead = buf.length - readBytesBuffer;
                    if (filterOutMetaData){
                        bytesToRead = Math.min(bytesUntilMetaData,bytesToRead);
                    }
                    readBytes = in.read(buf, readBytesBuffer, bytesToRead);
                    if (readBytes == 0) {
                        continue;
                    }
                    if (readBytes < 0) {
                        break;
                    }
                    readBytesBuffer += readBytes;
                    connectionBytesTotal += readBytes;
                    if (filterOutMetaData){
                        bytesUntilMetaData -= readBytes;
                    }

                    Log.v("ABC","in:"+readBytes);
                    if (readBytesBuffer > buf.length / 2) {
                        Log.v("ABC","out:"+readBytesBuffer);
                        out.write(buf, 0, readBytesBuffer);
                        readBytesBuffer = 0;
                    }
                }else
                {
                    int metadataBytes = in.read() * 16;
                    int metadataBytesToRead = metadataBytes;
                    readBytesBufferMetadata = 0;
                    bytesUntilMetaData = info.metadataOffset;
                    Log.d("ABC","metadata size:"+metadataBytes);
                    if (metadataBytes > 0) {
                        Arrays.fill(bufMetadata, (byte) 0);
                        while (true) {
                            readBytes = in.read(bufMetadata, readBytesBufferMetadata, metadataBytesToRead);
                            if (readBytes == 0) {
                                continue;
                            }
                            if (readBytes < 0) {
                                break;
                            }
                            metadataBytesToRead -= readBytes;
                            readBytesBufferMetadata += readBytes;
                            if (metadataBytesToRead <= 0) {
                                String s = new String(bufMetadata, 0, metadataBytes, "utf-8");
                                Log.d("ABC", "METADATA:" + s);
                                Map<String,String> dict = DecodeShoutcastMetadata(s);
                                Log.d("ABC", "META:"+dict.get("StreamTitle"));
                                callback.foundLiveStreamInfo(dict);
                                break;
                            }
                        }
                    }
                }
            }
        }catch(Exception e){
            Log.e("ABC",""+e);
        }
    }

    Map<String,String> DecodeShoutcastMetadata(String metadata){
        // icecast server does not encode "'" inside strings. so i am not able to check when a string ends
        //boolean stringStartedSingle = false;
        //boolean stringStartedDouble = false;
        String key = "";
        String value = "";
        boolean valueActive = false;

        Map<String,String> dict = new HashMap<String,String>();
        for (int i=0;i<metadata.length();i++){
            char c = metadata.charAt(i);

            /*if (stringStartedDouble || stringStartedSingle){
                if (c == '\'' && stringStartedSingle){
                    stringStartedSingle = false;
                } else if (c == '"' && stringStartedDouble){
                    stringStartedDouble = false;
                }else{
                    if (valueActive){
                        value += c;
                    }else{
                        key += c;
                    }
                }
            } else */{
                if (c == '\''){
                    //stringStartedSingle = true;
                } else if (c == '"'){
                    //stringStartedDouble = true;
                } else if (c == '=') {
                    valueActive = true;
                } else if (c == ';'){
                    valueActive = false;
                    dict.remove(key);
                    dict.put(key,value);
                    key = "";
                    value = "";
                } else {
                    if (valueActive){
                        value += c;
                    } else{
                        key += c;
                    }
                }
            }

        }
        if (valueActive){
            dict.remove(key);
            dict.put(key,value);
        }
        return dict;
    }

    public String getLocalAdress() {
        return localAdress;
    }
}
