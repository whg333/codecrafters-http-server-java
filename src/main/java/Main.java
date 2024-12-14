import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Main {

    public static final String CRLF = "\r\n";
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();

    private static String dir = "/";
    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    public static void main(String[] args) {
        if(args.length == 2){
            if("--directory".equals(args[0])){
                dir = args[1];
            }
        }
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
                String line = reader.readLine(); // readLine方法会读取到换行符为止，因此它非常适合读取起始行和头字段
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
                        debug("got empty line");
                        break;
                    }
                    line = reader.readLine();
                }
                Map<String, String> header = parseHeader(lines);

                boolean isPost = "POST".equals(requestLine.method());
                StringBuilder requestBody = new StringBuilder();
                if(isPost){
                    // 但是对于包含消息体的POST或PUT请求，消息体可能不以换行符结束，这就可能导致readLine正确读取消息体
                    // 所以这里读取Content-Length头部的值即消息体的长度，并使用reader.read读取稻字符数组缓冲
                    String contentLenStr = header.get("Content-Length");
                    if(contentLenStr != null){
                        int contentLen = Integer.parseInt(contentLenStr);
                        char[] charBuf = new char[contentLen];
                        int offset = 0;
                        while (offset < contentLen) {
                            int read = reader.read(charBuf, offset, contentLen - offset);
                            if (read == -1) break; // end of stream
                            offset += read;
                        }
                        requestBody.append(charBuf, 0, offset);
                        lines.add(requestBody.toString());
                    }
                }
                debug("recv [\n"+ String.join("\n", lines)+"\n]");

                String path = requestLine.path();
                if("/".equals(path)){
                    write("HTTP/1.1 200 OK"+CRLF+CRLF);
                }else{
                    String echo = "/echo/";
                    String files = "/files/";
                    if(path.startsWith(echo)){
                        String str = path.substring(echo.length());
                        byte[] respBytes = textResp(str, header);
                        writeBytes(respBytes);
                    }else if(path.startsWith("/user-agent")){
                        String agentStr = header.get("User-Agent");
                        byte[] respBytes = textResp(agentStr, header);
                        writeBytes(respBytes);
                    }else if(path.startsWith(files)){
                        String fileName = path.substring(files.length());
                        Path filePath = Path.of(dir, fileName);
                        File file = filePath.toFile();
                        if(isPost){ // POST method
                            Path dirPath = filePath.getParent();
                            File dirFile = dirPath.toFile();
                            if(!dirFile.exists()){
                                debug("create dir: "+dirFile.getAbsolutePath());
                                dirFile.mkdirs();
                            }
                            if(!file.exists()){
                                debug("create file: "+file.getAbsolutePath());
                                file.createNewFile();
                            }
                            Files.writeString(filePath, requestBody);
                            write("HTTP/1.1 201 Created"+CRLF+CRLF);
                        }else{ // GET method
                            if(file.exists()){
                                String respStr = fileResp(filePath);
                                write(respStr);
                            }else{
                                writeNotFound();
                            }
                        }
                    }else{
                        writeNotFound();
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

        private static Map<String, String> parseHeader(List<String> lines){
            Map<String, String> header = new HashMap<>();
            debug("parseHeader begin");
            for(String line: lines){
                String[] headerArr = line.split(":");
                if(headerArr.length == 2){
                    header.put(headerArr[0], headerArr[1].trim());
                }
            }
            debug("parseHeader end, "+header);
            return header;
        }

        private static byte[] textResp(String text, Map<String, String> header) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 200 OK").append(CRLF);

            sb.append("Content-Type: text/plain").append(CRLF);
            String acceptEncoding = header.get("Accept-Encoding");
            boolean isGzip = false;
            byte[] gzipTextBytes = new byte[0];
            if(acceptEncoding != null){
                String[] acceptEncodingArr = acceptEncoding.split(",");
                Set<String> acceptEncodingSet = Arrays.stream(acceptEncodingArr)
                        .map(String::trim).collect(Collectors.toSet());
                if(acceptEncodingSet.contains("gzip")){
                    sb.append("Content-Encoding: gzip").append(CRLF);
                    isGzip = true;
                    gzipTextBytes = gzipCompress(text);
                }
            }

            byte[] textBytes = text.getBytes(UTF_8);
            sb.append("Content-Length: ").append(isGzip ? gzipTextBytes.length : textBytes.length).append(CRLF);
            sb.append(CRLF); // CRLF that marks the end of the headers

            // sb.append(text); // response body
            ByteBuffer msgBuf = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN);
            byte[] respBytes = sb.toString().getBytes(UTF_8);
            msgBuf.put(respBytes);
            if(isGzip){
                msgBuf.put(gzipTextBytes);
            }else{
                msgBuf.put(textBytes);
            }

            msgBuf.flip();
            byte[] msgBytes = new byte[msgBuf.remaining()];
            msgBuf.get(msgBytes);
            return msgBuf.array();
        }

        private static String fileResp(Path filePath) throws IOException {
            String fileContent = Files.readString(filePath);

            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 200 OK").append(CRLF);

            sb.append("Content-Type: application/octet-stream").append(CRLF);
            sb.append("Content-Length: ").append(fileContent.getBytes(UTF_8).length).append(CRLF);
            sb.append(CRLF); // CRLF that marks the end of the headers

            sb.append(fileContent); // response body
            return sb.toString();
        }

        private void writeNotFound() throws IOException {
            write("HTTP/1.1 404 Not Found"+CRLF+CRLF);
        }

        private void write(String respStr) throws IOException {
            byte[] response = respStr.getBytes(UTF_8);
            writeBytes(response);
        }

        private void writeBytes(byte[] response) throws IOException {
            client.getOutputStream().write(response);
            debug("send [\n"+new String(response, UTF_8)+"\n]");
        }

        private void closeClient(){
            try {
                client.close();
                debug("closed client: "+client);
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

    // gzip压缩字符串
    public static byte[] gzipCompress(String str) throws IOException {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
            gzipOutputStream.write(str.getBytes(UTF_8));
            gzipOutputStream.finish();
            return byteArrayOutputStream.toByteArray();
        }
    }

    // gzip解压缩字符串
    public static String gzipDecompress(byte[] compressed) throws IOException {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, len);
            }
            return byteArrayOutputStream.toString(UTF_8);
        }
    }

    private static void debug(String msg){
        System.out.println(msg);
    }
    private static void error(String msg, Exception e){
        System.err.println(msg);
        e.printStackTrace();
    }

}
