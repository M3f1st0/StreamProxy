

/**
 * Created by Panagiotis Bitharis on 21/3/2016.
 */
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;


public class StreamProxy implements Runnable {
    private String TAG="Stream Proxy";

    private static final int SERVER_PORT=8888;

    private Thread thread;
    private boolean isRunning;
    private ServerSocket socket;
    private int port;
    private long size;
    private String mimeType;
    private static final Path homeDirectory = Paths.get("C:" + File.separator + "Uploads" + File.separator);
    private int cbSkip=0;
    
    

    public StreamProxy() {
//        size = contenLength;
//        mimeType = MimeType;



        // Create listening socket
        try {
            socket = new ServerSocket(SERVER_PORT);
            socket.setSoTimeout(5000);
            port = socket.getLocalPort();
        } catch (UnknownHostException e) { // impossible
        } catch (IOException e) {
            System.out.println("IOException initializing server");
        }

    }

    public void start() {
        thread = new Thread(this);
        thread.start();
        System.out.println("Initializing");
    }

    public void stop() {
        isRunning = false;
        thread.interrupt();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        //Looper.prepare();
        isRunning = true;
        while (isRunning) {
            try {
                Socket client = socket.accept();
                System.out.println("Waiting for connection at 192.168.1.2:8888");
                if (client == null) {
                    continue;
                }
                System.out.println("client connected");

                StreamToMediaPlayerTask task = new StreamToMediaPlayerTask(client);
                if (task.processRequest()) {
                    task.doInBackground();
                    System.out.println("Proxy Async is executed");
                }

            } catch (SocketTimeoutException e) {
                // Do nothing
            } catch (IOException e) {
                System.out.println("Error connecting to client");
                System.out.println(e.getMessage());
            }
        }
        System.out.println("Proxy interrupted. Shutting down.");
    }

    private class StreamToMediaPlayerTask {

        String fileName;
        Socket client;
        

        public StreamToMediaPlayerTask(Socket client) {
            this.client = client;
        }

        public boolean processRequest() throws IOException {
            // Read HTTP headers
            ArrayList<String> headers ;
            try {
                headers = Utils(client.getInputStream());

            } catch (IOException e) {
                System.out.println("Error reading HTTP request header from stream:");
                return false;
            }

            // Get the important bits from the headers
           // String[] headerLines = headers.split("\n");
            String[] headerLines = new String[headers.size()];
            for(int i=0; i<headers.size(); i++){
                headerLines[i] = headers.get(i);
                //System.out.println("HEADERLINE "+headerLines[i]);
            }
            String urlLine = headerLines[0];
            if (!urlLine.startsWith("GET ")) {
                System.out.println("Only GET is supported");
                return false;
            }
            //System.out.println("HEADER three: "+urlLine);
            urlLine = urlLine.substring(4);
            int charPos = urlLine.indexOf(' ');
            if (charPos != -1) {
                urlLine = urlLine.substring(1, charPos);
            }
            fileName = urlLine;
            size = extractContentLength(fileName);
            mimeType = extractMIMEtype(fileName);
            System.out.println("Size: "+size);
            System.out.println("mimeType: "+mimeType);
            //System.out.println("HEADER four: "+fileName);

            // See if there's a "Range:" header
            for (int i=0 ; i<headerLines.length ; i++) {
                String headerLine = headerLines[i];
                if (headerLine.startsWith("Range: bytes=")) {
                    headerLine = headerLine.substring(13);
                    charPos = headerLine.indexOf('-');
                    if (charPos>0) {
                        headerLine = headerLine.substring(0,charPos);
                    }
                    cbSkip = Integer.parseInt(headerLine);
                    System.out.println("cbSkip "+cbSkip);
                }
            }
            return true;
        }

        
        protected Integer doInBackground() {

            long fileSize = size;

            // Create HTTP header
            String headers = "HTTP/1.0 200 OK\r\n";
            headers += "Content-Type: " + mimeType + "\r\n";
            headers += "Content-Length: " + size  + "\r\n";
            headers += "Connection: close\r\n";
            headers += "\r\n";

            System.out.println("HEADERS SIX: "+headers);

            // Begin with HTTP header
            int fc = 0;
            long cbToSend = fileSize - cbSkip;
            OutputStream output = null;
            byte[] buff = new byte[64 * 1024];
            try {
                output = new BufferedOutputStream(client.getOutputStream(),32 * 1024);
                output.write(headers.getBytes());
                System.out.println("Headers sent...");

                // Loop as long as there's stuff to send
                while (isRunning && cbToSend>0 && !client.isClosed()) {

                    // See if there's more to send
                    File file = new File(homeDirectory+File.separator+fileName);
                    fc++;
                    int cbSentThisBatch = 0;
                    if (file.exists()) {
                        FileInputStream input = new FileInputStream(file);
                        input.skip(cbSkip);
                        int cbToSendThisBatch = input.available();
                        while (cbToSendThisBatch > 0) {
                            int cbToRead = Math.min(cbToSendThisBatch, buff.length);
                            int cbRead = input.read(buff, 0, cbToRead);
                            if (cbRead == -1) {
                                break;
                            }
                            cbToSendThisBatch -= cbRead;
                            cbToSend -= cbRead;
                            output.write(buff, 0, cbRead);
                            output.flush();
                            cbSkip += cbRead;
                            cbSentThisBatch += cbRead;
                        }
                        System.out.println("Closing input");
                        input.close();
                        
                    }

                    // If we did nothing this batch, block for a second
                    if (cbSentThisBatch == 0) {
                        System.out.println("Blocking until more data appears");
                        Thread.sleep(1000);
                    }
                }
            }
            catch (SocketException socketException) {
                System.out.println("SocketException() thrown, proxy client has probably closed. This can exit harmlessly");
            }
            catch (Exception e) {
                System.out.println( "Exception thrown from streaming task:");
                System.out.println( e.getClass().getName() + " : " + e.getLocalizedMessage());
                e.printStackTrace();
            }

            // Cleanup
//            try {
//                if (output != null) {
//                    System.out.println("closing output");
//                    output.close();
//                }
//                
//                client.close();
//                System.out.println("closed connection");
//            }
//            catch (IOException e) {
//                System.out.println( "IOException while cleaning up streaming task:");
//                System.out.println( e.getClass().getName() + " : " + e.getLocalizedMessage());
//                e.printStackTrace();
//            }

            return 1;
        }

        public ArrayList<String> Utils(InputStream input) throws IOException {
            ArrayList<String> request= new ArrayList<>();
            BufferedReader bufferedReaderInput = new BufferedReader(new InputStreamReader(input));


            while (true) {

                String readLine = bufferedReaderInput.readLine();
                if (readLine == null || readLine.length() == 0) {

                    break;
                }else{

                    request.add(readLine);

                }
                //System.out.println("HEADERS two: "+request);

            }

            return request;
        }
        
        private String extractMIMEtype(String requestedFileName) throws IOException{
            String mimeType = Files.probeContentType(Paths.get(homeDirectory+File.separator+requestedFileName));
            return mimeType;
        }
        
        private long extractContentLength(String requestedFileName) throws IOException{
            long size = Files.size(Paths.get(homeDirectory+File.separator+requestedFileName));
            return size;
        }

    }


}
