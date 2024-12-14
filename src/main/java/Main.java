import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static final String CRLF = "\r\n";
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        try {
            ServerSocket serverSocket = new ServerSocket(4221);
            debug("http server start on port: "+serverSocket.getLocalPort());

            // Since the tester restarts your program quite often, setting SO_REUSEADDR
            // ensures that we don't run into 'Address already in use' errors
            serverSocket.setReuseAddress(true);

            while(true){
                // Wait for connection from client.
                threadPool.execute(new ClientProcessor(serverSocket.accept()));
            }
        } catch (IOException e) {
            error("IOException: " + e.getMessage(), e);
        }
    }

    private static class ClientProcessor implements Runnable {

        private final Socket client;

        private ClientProcessor(Socket client){
            this.client = client;
        }

        @Override
        public void run() {
            debug("handle client: "+client);
            try {
                // client.setSoTimeout(5000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                List<String> lines = new ArrayList<>();
                String line = reader.readLine();
                if(isEmpty(line)){
                    return;
                }
                debug(line);
                RequestLine requestLine = new RequestLine(line);

                line = reader.readLine();
                while (!isEmpty(line)) {
                    debug(line);
                    lines.add(line);
                    line = reader.readLine();
                }
                // debug("recv: "+ lines);

                if("/".equals(requestLine.path())){
                    write("HTTP/1.1 200 OK"+CRLF+CRLF);
                }else{
                    write("HTTP/1.1 404 Not Found"+CRLF+CRLF);
                }

                closeClient();
            } catch (IOException e) {
                error("IOException: " + e.getMessage(), e);
                closeClient();
            }
        }

        private boolean isEmpty(String str){
            return str == null || str.equals("");
        }

        private void process(List<String> lines) {
            debug("lines: "+lines);
        }

        private void write(String respStr) throws IOException {
            byte[] response = respStr.getBytes(StandardCharsets.UTF_8);
            client.getOutputStream().write(response);
            debug("send "+respStr);
        }

        private void closeClient(){
            try {
                client.close();
                debug("close client: "+client);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private static class RequestLine{
        String[] lineArr;
        RequestLine(String requestLine){
            lineArr = requestLine.split(" ");
            if(lineArr.length != 3){
                throw new IllegalArgumentException("parse request line error: "+requestLine);
            }
        }
        String method(){
            return lineArr[0];
        }
        String path(){
            return lineArr[1];
        }
        String version(){
            return lineArr[2];
        }
    }

    private static void debug(String msg){
        System.out.println(msg);
    }
    private static void error(String msg, Exception e){
        System.err.println(msg);
        // e.printStackTrace();
    }

}
