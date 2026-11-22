package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Emulates a UPnP Internet Gateway Device on 127.0.0.1: an SSDP socket that answers every search with the description's location, and an
 * HTTP server with the description and the SOAP control of one WAN connection service ({@code GetExternalIPAddress}, {@code AddPortMapping},
 * {@code AddAnyPortMapping}, {@code DeletePortMapping}, {@code GetGenericPortMappingEntry}). It keeps a table of mappings, can refuse external
 * ports with error 718 and leases other than {@code 0} with error 725, and records every action with its arguments.
 */
final class UpnpDeviceScenario implements Closeable {
    /**
     * Holds one SOAP action the device received: its name and arguments.
     */
    static final class SoapCall {
        private final @NotNull String action;
        private final @NotNull Map<@NotNull String, @NotNull String> arguments;

        private SoapCall(@NotNull String action, @NotNull Map<@NotNull String, @NotNull String> arguments) {
            this.action    = action;
            this.arguments = arguments;
        }

        @NotNull String getAction() {
            return this.action;
        }

        @NotNull String getArgument(@NotNull String name) {
            return this.arguments.getOrDefault(name, "");
        }
    }

    static final @NotNull String VERSION_1_SERVICE = "urn:schemas-upnp-org:service:WANIPConnection:1";
    static final @NotNull String VERSION_2_SERVICE = "urn:schemas-upnp-org:service:WANIPConnection:2";

    private static final @NotNull InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");
    private static final @NotNull String THREAD_NAME = "upnp-device-scenario";
    private static final @NotNull String DESCRIPTION_PATH = "/rootDesc.xml";
    private static final @NotNull String CONTROL_PATH = "/ctl/IPConn";
    private static final @NotNull Pattern ARGUMENT = Pattern.compile("<(New[A-Za-z]+)>([^<]*)</\\1>");
    private static final int RECEIVE_TIMEOUT_MILLIS = 50;
    private static final int BUFFER_LENGTH = 2048;
    private static final int HTTP_OK = 200;
    private static final int HTTP_ERROR = 500;
    private static final int ERROR_INVALID_INDEX = 713;
    private static final int ERROR_NO_SUCH_ENTRY = 714;
    private static final int ERROR_CONFLICT = 718;
    private static final int ERROR_ONLY_PERMANENT_LEASES = 725;
    private static final @NotNull String PERMANENT_LEASE = "0";

    /**
     * Opens a device on free 127.0.0.1 ports.
     *
     * @param externalAddress the external address the device reports
     * @param serviceType     the type of its WAN connection service
     * @return the opened device
     * @throws IOException if a socket cannot be bound
     */
    static @NotNull UpnpDeviceScenario open(@NotNull InetAddress externalAddress, @NotNull String serviceType) throws IOException {
        UpnpDeviceScenario device = new UpnpDeviceScenario(externalAddress, serviceType);

        device.http.start();
        device.thread.start();

        return device;
    }

    private static void send(@NotNull HttpExchange exchange, int status, @NotNull String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=\"utf-8\"");
        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }


    private final @NotNull InetAddress externalAddress;
    private final @NotNull String serviceType;
    private final @NotNull DatagramSocket search;
    private final @NotNull HttpServer http;
    private final @NotNull Thread thread;

    private final @NotNull List<@NotNull SoapCall> calls;
    private final @NotNull List<@NotNull Map<@NotNull String, @NotNull String>> entries;
    private final @NotNull Set<@NotNull Integer> conflictPorts;
    private volatile boolean permanentOnly;
    private volatile int reservedPort;
    private volatile boolean closed;

    private UpnpDeviceScenario(@NotNull InetAddress externalAddress, @NotNull String serviceType) throws IOException {
        this.externalAddress = externalAddress;
        this.serviceType     = serviceType;
        this.search          = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
        this.http            = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        this.thread          = Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(this::answerSearches);

        this.calls         = new ArrayList<>();
        this.entries       = new ArrayList<>();
        this.conflictPorts = new HashSet<>();
        this.permanentOnly = false;
        this.reservedPort  = 0;
        this.closed        = false;

        this.search.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
        this.http.createContext(DESCRIPTION_PATH, this::describe);
        this.http.createContext(CONTROL_PATH, this::control);
    }

    /**
     * @return the address the SSDP search goes to
     */
    @NotNull InetSocketAddress getSearchAddress() {
        return (InetSocketAddress) this.search.getLocalSocketAddress();
    }

    /**
     * Makes the device refuse an external port with error 718 when {@code AddPortMapping} asks for it.
     */
    void addConflictPort(int port) {
        synchronized (this.calls) {
            this.conflictPorts.add(port);
        }
    }

    /**
     * Makes the device refuse leases other than {@code 0} with error 725.
     */
    void setPermanentOnly(boolean permanentOnly) {
        this.permanentOnly = permanentOnly;
    }

    /**
     * Sets the external port {@code AddAnyPortMapping} grants.
     */
    void setReservedPort(int reservedPort) {
        this.reservedPort = reservedPort;
    }

    /**
     * Adds a mapping to the device's table, as another program or an earlier run left it.
     */
    void addEntry(int externalPort, int internalPort, @NotNull String client, @NotNull String description) {
        Map<String, String> entry = new LinkedHashMap<>();

        entry.put("NewRemoteHost", "");
        entry.put("NewExternalPort", Integer.toString(externalPort));
        entry.put("NewProtocol", "UDP");
        entry.put("NewInternalPort", Integer.toString(internalPort));
        entry.put("NewInternalClient", client);
        entry.put("NewEnabled", "1");
        entry.put("NewPortMappingDescription", description);
        entry.put("NewLeaseDuration", PERMANENT_LEASE);

        synchronized (this.calls) {
            this.entries.add(entry);
        }
    }

    /**
     * @return the actions the device received, in order
     */
    @NotNull List<@NotNull SoapCall> getCalls() {
        synchronized (this.calls) {
            return List.copyOf(this.calls);
        }
    }

    /**
     * @return the actions of a name the device received, in order
     */
    @NotNull List<@NotNull SoapCall> getCalls(@NotNull String action) {
        return this.getCalls().stream().filter(call -> call.getAction().equals(action)).toList();
    }

    /**
     * @return the external ports of the device's table
     */
    @NotNull List<@NotNull Integer> getExternalPorts() {
        synchronized (this.calls) {
            return this.entries.stream().map(entry -> Integer.parseInt(entry.get("NewExternalPort"))).toList();
        }
    }


    private void answerSearches() {
        byte[] buffer = new byte[BUFFER_LENGTH];
        InetSocketAddress server = this.http.getAddress();

        while (!this.closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            try {
                this.search.receive(packet);

                byte[] answer = ("HTTP/1.1 200 OK\r\n" +
                                 "CACHE-CONTROL: max-age=120\r\n" +
                                 "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n" +
                                 "Location: http://127.0.0.1:" + server.getPort() + DESCRIPTION_PATH + "\r\n" +
                                 "\r\n").getBytes(StandardCharsets.ISO_8859_1);

                this.search.send(new DatagramPacket(answer, answer.length, packet.getSocketAddress()));
            } catch (SocketTimeoutException exception) {
                // Look at the closed flag again.
            } catch (IOException exception) {
                return;
            }
        }
    }

    private void describe(@NotNull HttpExchange exchange) throws IOException {
        send(exchange, HTTP_OK, """
                                <?xml version="1.0"?>
                                <root xmlns="urn:schemas-upnp-org:device-1-0">
                                <device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
                                <serviceList><service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
                                <controlURL>/ctl/L3F</controlURL></service></serviceList>
                                <deviceList><device><deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType>
                                <deviceList><device><deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType>
                                <serviceList><service><serviceType>%s</serviceType><controlURL>%s</controlURL></service></serviceList>
                                </device></deviceList></device></deviceList></device></root>
                                """.formatted(this.serviceType, CONTROL_PATH));
    }

    private void control(@NotNull HttpExchange exchange) throws IOException {
        String soapAction = Objects.requireNonNullElse(exchange.getRequestHeaders().getFirst("SOAPAction"), "");
        String action = soapAction.substring(soapAction.indexOf('#') + 1).replace("\"", "");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> arguments = new LinkedHashMap<>();
        Matcher matcher = ARGUMENT.matcher(body);

        while (matcher.find()) {
            arguments.put(matcher.group(1), matcher.group(2));
        }

        Map<String, String> result;
        int error;

        synchronized (this.calls) {
            this.calls.add(new SoapCall(action, arguments));
            result = new LinkedHashMap<>();
            error  = this.act(action, arguments, result);
        }

        if (error != 0) {
            send(exchange, HTTP_ERROR, """
                                       <?xml version="1.0"?>
                                       <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode>
                                       <faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                                       <errorCode>%d</errorCode><errorDescription>Error</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>
                                       """.formatted(error));

            return;
        }

        StringBuilder values = new StringBuilder();

        for (Map.Entry<String, String> value : result.entrySet()) {
            values.append('<').append(value.getKey()).append('>').append(value.getValue()).append("</").append(value.getKey()).append('>');
        }

        send(exchange, HTTP_OK, """
                                <?xml version="1.0"?>
                                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:%sResponse xmlns:u="%s">%s</u:%sResponse>
                                </s:Body></s:Envelope>
                                """.formatted(action, this.serviceType, values, action));
    }

    /**
     * Carries out an action on the device's table.
     *
     * @return the UPnP error code, or {@code 0} on success
     */
    private int act(@NotNull String action, @NotNull Map<@NotNull String, @NotNull String> arguments,
                    @NotNull Map<@NotNull String, @NotNull String> result) {
        switch (action) {
            case "GetExternalIPAddress" -> {
                result.put("NewExternalIPAddress", this.externalAddress.getHostAddress());

                return 0;
            }
            case "AddPortMapping", "AddAnyPortMapping" -> {
                boolean any = action.equals("AddAnyPortMapping");
                String externalPort = any ? Integer.toString(this.reservedPort) : arguments.get("NewExternalPort");

                if (this.permanentOnly && !PERMANENT_LEASE.equals(arguments.get("NewLeaseDuration"))) {
                    return ERROR_ONLY_PERMANENT_LEASES;
                }

                if (!any && externalPort != null && this.conflictPorts.contains(Integer.parseInt(externalPort))) {
                    return ERROR_CONFLICT;
                }

                Map<String, String> entry = new LinkedHashMap<>(arguments);

                entry.put("NewExternalPort", Objects.requireNonNullElse(externalPort, ""));
                this.entries.removeIf(existing -> existing.get("NewExternalPort").equals(entry.get("NewExternalPort")));
                this.entries.add(entry);

                if (any) {
                    result.put("NewReservedPort", externalPort);
                }

                return 0;
            }
            case "DeletePortMapping" -> {
                boolean removed = this.entries.removeIf(entry -> entry.get("NewExternalPort").equals(arguments.get("NewExternalPort")));

                return removed ? 0 : ERROR_NO_SUCH_ENTRY;
            }
            case "GetGenericPortMappingEntry" -> {
                int index = Integer.parseInt(arguments.getOrDefault("NewPortMappingIndex", "-1"));

                if (index < 0 || index >= this.entries.size()) {
                    return ERROR_INVALID_INDEX;
                }

                result.putAll(this.entries.get(index));

                return 0;
            }
            default -> {
                return ERROR_NO_SUCH_ENTRY;
            }
        }
    }


    /**
     * Stops the search thread and the HTTP server.
     */
    @Override
    public void close() {
        this.closed = true;
        this.search.close();
        this.http.stop(0);

        try {
            this.thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
