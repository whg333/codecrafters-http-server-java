import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static final String CRLF = "\r\n";
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();

    private static final Map<String, String> header = new HashMap<>();

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
                if(line == null){
                    closeClient();
                    return;
                }
                // debug(line);
                lines.add(line);
                RequestLine requestLine = new RequestLine(line);

                line = reader.readLine();
                while (line != null) {
                    // debug(line);
                    lines.add(line);
                    if("".equals(line)){
                        break;
                    }
                    line = reader.readLine();
                }
                parseHeader(lines);

                line = reader.readLine();
                if(line == null){
                    closeClient();
                    return;
                }
                lines.add(line);
                debug("recv [\n"+ String.join("\n", lines)+"\n]");

                String path = requestLine.path();
                if("/".equals(path)){
                    write("HTTP/1.1 200 OK"+CRLF+CRLF);
                }else{
                    String echo = "/echo/";
                    if(path.startsWith(echo)){
                        String str = path.substring(echo.length());
                        String respStr = textResp(str);
                        write(respStr);
                    }else if(path.startsWith("/user-agent")){
                        String agentStr = header.get("User-Agent");
                        String respStr = textResp(agentStr);
                        write(respStr);
                    }else{
                        write("HTTP/1.1 404 Not Found"+CRLF+CRLF);
                    }
                }

                closeClient();
            } catch (IOException e) {
                error("IOException: " + e.getMessage(), e);
                closeClient();
            }
        }

        private void process(List<String> lines) {
            debug("lines: "+lines);
        }

        private static void parseHeader(List<String> lines){
            for(String line: lines){
                String[] headerArr = line.split(":");
                if(headerArr.length == 2){
                    header.put(headerArr[0], headerArr[1].trim());
                }
            }
        }

        private static String textResp(String text){
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 200 OK").append(CRLF);

            sb.append("Content-Type: text/plain").append(CRLF);
            sb.append("Content-Length: "+text.getBytes().length).append(CRLF);
            sb.append(CRLF); // CRLF that marks the end of the headers

            sb.append(text); // response body
            return sb.toString();
        }

        private void write(String respStr) throws IOException {
            byte[] response = respStr.getBytes(StandardCharsets.UTF_8);
            client.getOutputStream().write(response);
            debug("send [\n"+respStr+"\n]");
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
