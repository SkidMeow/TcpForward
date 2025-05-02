package top.infinityorigin.tcpforward;

import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

//114514
public class ProxyServer {
    private static final String CONFIG_FILE = "config.yml";
    private static final String version = "1.0";
    private static final String DEFAULT_CONFIG =
            "config-ver: \"1.0\"\n" +
                    "port: 25565\n" +
                    "forward-server:\n" +
                    "  ip: \"example.com\"\n" +
                    "  port: 25565\n";

    private static PrintWriter logger;
    private static String configPath;

    static class ProxyConfig {
        String configVer;
        int port;
        ForwardServer forwardServer;

        static class ForwardServer {
            String ip;
            int port;
        }
    }

    public static void main(String[] args) {
        initializeLogger();
        log("The forward/transfer server started at " + LocalDateTime.now(), true);

        ProxyConfig config = loadOrCreateConfig();
        if (config == null || !validateConfig(config)) {
            log("Invalid configuration", true);
            return;
        }

        ExecutorService executor = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(config.port)) {
            printStartupMessage(config);

            while (!serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                executor.execute(() -> handleConnection(clientSocket, config));
            }
        } catch (IOException e) {
            log("Server error: " + e.getMessage(), false);
        } finally {
            executor.shutdown();
            if (logger != null) logger.close();
        }
    }

    private static void initializeLogger() {
        try {
            String dir = new File(ProxyServer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
            File logFile = new File(dir, "logging.txt");
            logger = new PrintWriter(new FileWriter(logFile, true), true);
            configPath = new File(dir, CONFIG_FILE).getAbsolutePath();
        } catch (Exception e) {
            System.err.println("Failed to initialize logger: " + e.getMessage());
        }
    }

    private static void log(String message, boolean consoleOutput) {
        String tips = "我嘞个豆";
        if (logger != null) {
            logger.println("[" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) + "] " + message);
        }
        if (consoleOutput) {
            System.out.println(message);
        }
    }

    private static ProxyConfig loadOrCreateConfig() {
        try {
            File configFile = new File(configPath);
            if (!configFile.exists()) {
                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write(DEFAULT_CONFIG);
                    log("Created default config file at: " + configPath, true);
                }
            }

            Yaml yaml = new Yaml();
            try (InputStream input = Files.newInputStream(configFile.toPath())) {
                Map<String, Object> configMap = yaml.load(input);
                return parseConfigMap(configMap);
            }
        } catch (Exception e) {
            log("Config loading failed: " + e.getMessage(), true);
            return null;
        }
    }

    private static ProxyConfig parseConfigMap(Map<String, Object> configMap) {
        ProxyConfig config = new ProxyConfig();
        try {
            config.configVer = (String) configMap.get("config-ver");
            config.port = ((Number) configMap.get("port")).intValue();

            Map<String, Object> forwardMap = (Map<String, Object>) configMap.get("forward-server");
            config.forwardServer = new ProxyConfig.ForwardServer();
            config.forwardServer.ip = ((String) forwardMap.get("ip")).trim();
            config.forwardServer.port = ((Number) forwardMap.get("port")).intValue();

            return config;
        } catch (Exception e) {
            log("Config parsing error: " + e.getMessage(), true);
            return null;
        }
    }

    private static boolean validateConfig(ProxyConfig config) {
        try {
            if (config.port < 1 || config.port > 65535) {
                log("Invalid local port: " + config.port, false);
                return false;
            }

            if (config.forwardServer == null) {
                log("Missing forward-server configuration", false);
                return false;
            }

            if (config.forwardServer.port < 1 || config.forwardServer.port > 65535) {
                log("Invalid remote port: " + config.forwardServer.port, false);
                return false;
            }

            return true;
        } catch (Exception e) {
            log("Configuration validation failed: " + e.getMessage(), false);
            return false;
        }
    }

    private static void printStartupMessage(ProxyConfig config) {
        String msg = String.format(
                "TcpForward v%s\nListening: 0.0.0.0:%d\nForwarding: %s:%d\nby SkidMeow\n",
                version, config.port, config.forwardServer.ip, config.forwardServer.port
        );
        log(msg, true);
    }

    private static void handleConnection(Socket clientSocket, ProxyConfig config) {
        AtomicBoolean isActive = new AtomicBoolean(true);
        Socket serverSocket = null;

        try {
            serverSocket = new Socket(config.forwardServer.ip, config.forwardServer.port);
            log(String.format("Connection established %s <-> %s",
                    clientSocket.getRemoteSocketAddress(),
                    serverSocket.getRemoteSocketAddress()), true);

            clientSocket.setTcpNoDelay(true);
            serverSocket.setTcpNoDelay(true);

            Socket finalServerSocket = serverSocket;
            Thread clientToServer = new Thread(() -> {
                transfer(clientSocket, finalServerSocket, isActive, "Client->Server");
                safeClose(clientSocket, finalServerSocket, isActive);
            });

            Socket finalServerSocket1 = serverSocket;
            Thread serverToClient = new Thread(() -> {
                transfer(finalServerSocket1, clientSocket, isActive, "Server->Client");
                safeClose(clientSocket, finalServerSocket1, isActive);
            });

            clientToServer.start();
            serverToClient.start();

            while (isActive.get()) {
                if (!clientToServer.isAlive() || !serverToClient.isAlive()) {
                    log("Connection exception: Forwarding thread terminated", true);
                    break;
                }
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            log("Connection error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        } finally {
            safeClose(clientSocket, serverSocket, isActive);
        }
    }

    private static void transfer(Socket src, Socket dest, AtomicBoolean flag, String direction) {
        try (InputStream in = src.getInputStream();
             OutputStream out = dest.getOutputStream()) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while (flag.get() && (bytesRead = in.read(buffer)) != -1) {
                synchronized (out) {
                    out.write(buffer, 0, bytesRead);
                    out.flush();
                }
            }
        } catch (IOException e) {
            if (e instanceof SocketException) {
                log("Safe closure: " + direction + ": " + e.getMessage(), true);
            } else {
                log("Transfer exception: " + direction + ": " + e.getClass().getSimpleName(), true);
            }
        } finally {
            flag.set(false);
        }
    }

    private static synchronized void safeClose(Socket client, Socket server, AtomicBoolean flag) {
        if (flag.compareAndSet(true, false)) {
            try {
                if (client != null && !client.isClosed()) {
                    client.shutdownInput();
                    client.shutdownOutput();
                    Thread.sleep(200);
                    client.close();
                }
                if (server != null && !server.isClosed()) {
                    server.shutdownInput();
                    server.shutdownOutput();
                    Thread.sleep(200);
                    server.close();
                }
                log("Connection closed: " + LocalDateTime.now(), false);
            } catch (Exception e) {
                log("Close warning: " + e.getMessage(), false);
            }
        }
    }
}
