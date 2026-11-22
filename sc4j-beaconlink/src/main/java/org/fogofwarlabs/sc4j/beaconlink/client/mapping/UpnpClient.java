package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Maps a UDP port with UPnP Internet Gateway Device versions 1 and 2: an SSDP search finds the device, its description names the control URL
 * of its WAN connection service ({@code WANIPConnection} version 2 first, then version 1, then {@code WANPPPConnection}), and SOAP actions
 * read the external address ({@code GetExternalIPAddress}), map ({@code AddPortMapping}, {@code AddAnyPortMapping} on version 2), renew
 * (the same {@code AddPortMapping}) and delete ({@code DeletePortMapping}). The mapping's internal client is the local address facing the
 * device. A conflict (error {@value #ERROR_CONFLICT}) is answered with another external port, or on version 2 with
 * {@code AddAnyPortMapping}; a device that takes only permanent leases (error {@value #ERROR_ONLY_PERMANENT_LEASES}) is asked again with lease
 * {@code 0}.
 * <p>
 * Before mapping, the client removes leftover mappings of this computer: the device's mappings ({@code GetGenericPortMappingEntry}) of the
 * UDP protocol to the local address with the configured description whose internal port no socket of this computer holds.
 * <p>
 * The device is trusted only at the address that answered the search: its description and control URLs must name that address.
 */
final class UpnpClient implements MappingClient {
    private static final @NotNull Logger LOGGER = Logger.of(UpnpClient.class);
    private static final @NotNull List<@NotNull String> SEARCH_TARGETS = List.of("urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                                                                                 "urn:schemas-upnp-org:device:InternetGatewayDevice:2");
    private static final @NotNull List<@NotNull String> SERVICE_TYPES = List.of("urn:schemas-upnp-org:service:WANIPConnection:2",
                                                                                "urn:schemas-upnp-org:service:WANIPConnection:1",
                                                                                "urn:schemas-upnp-org:service:WANPPPConnection:1");
    private static final @NotNull String VERSION_2_SERVICE_TYPE = "urn:schemas-upnp-org:service:WANIPConnection:2";
    private static final @NotNull String HTTP_SCHEME = "http";
    private static final @NotNull String SEARCH_OK_PREFIX = "HTTP/1.1 200";
    private static final @NotNull String LOCATION_HEADER = "location:";
    private static final @NotNull String UDP_PROTOCOL = "UDP";
    private static final @NotNull String ANY_REMOTE_HOST = "";
    private static final @NotNull String ENABLED = "1";
    private static final @NotNull String NO_ADDRESS = "0.0.0.0";

    /**
     * The SOAP error of a mapping whose external port another client holds.
     */
    private static final int ERROR_CONFLICT = 718;
    /**
     * The SOAP error of a device that takes only mappings with lease {@code 0}.
     */
    private static final int ERROR_ONLY_PERMANENT_LEASES = 725;
    private static final int HTTP_OK = 200;
    /**
     * The time an HTTP connection or request to the device may take, in milliseconds: longer than the datagram answer time, since the first
     * HTTP exchange of a process also starts the HTTP client.
     */
    private static final long HTTP_TIMEOUT_MILLIS = 8_000L;
    private static final int SEARCH_WAIT_SECONDS = 1;
    private static final int SEARCH_SENDS = 2;
    private static final int MAXIMUM_SEARCH_ANSWER_LENGTH = 2048;
    private static final int MAXIMUM_DOCUMENT_LENGTH = 65_536;
    private static final int MAXIMUM_ADD_ATTEMPTS = 4;
    private static final int MAXIMUM_LISTED_MAPPINGS = 256;
    private static final int DYNAMIC_PORT_MINIMUM = 49_152;
    private static final int DYNAMIC_PORT_COUNT = 16_384;
    private static final int PERMANENT_LEASE = 0;
    private static final int MAXIMUM_NUMBER_DIGITS = 9;

    /**
     * Holds the answer to a SOAP action: the HTTP status, the UPnP error code of a fault and the parsed document.
     */
    private static final class SoapAnswer {
        private final int status;
        private final int errorCode;
        private final @Nullable Document document;

        private SoapAnswer(int status, int errorCode, @Nullable Document document) {
            this.status    = status;
            this.errorCode = errorCode;
            this.document  = document;
        }

        private boolean isSuccess() {
            return this.status == HTTP_OK && this.document != null;
        }

        private @Nullable String readValue(@NotNull String name) {
            return this.document == null ? null : readText(this.document.getDocumentElement(), name);
        }

        private @NotNull String describe() {
            return this.errorCode > 0 ? "error " + this.errorCode : "HTTP " + this.status;
        }
    }

    private static @NotNull DocumentBuilder createDocumentBuilder() throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        try {
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException exception) {
            throw Exceptions.newIOException(exception, "The XML parser cannot be configured: {0}", exception.getMessage());
        }
    }

    private static @NotNull Document parseDocument(byte @NotNull [] contents) throws IOException {
        try {
            return createDocumentBuilder().parse(new ByteArrayInputStream(contents));
        } catch (SAXException exception) {
            throw Exceptions.newIOException(exception, "Invalid XML: {0}", exception.getMessage());
        }
    }

    /**
     * Reads the trimmed text of the first element of a local name below an element.
     */
    private static @Nullable String readText(@NotNull Element parent, @NotNull String name) {
        NodeList elements = parent.getElementsByTagNameNS("*", name);

        return elements.getLength() == 0 ? null : elements.item(0).getTextContent().strip();
    }

    /**
     * Reads a response body of at most {@value #MAXIMUM_DOCUMENT_LENGTH} bytes.
     */
    private static byte @NotNull [] readBody(@NotNull HttpResponse<InputStream> response) throws IOException {
        try (InputStream input = response.body()) {
            byte[] contents = input.readNBytes(MAXIMUM_DOCUMENT_LENGTH + 1);

            if (contents.length > MAXIMUM_DOCUMENT_LENGTH) {
                throw Exceptions.newIOException("The answer is longer than {0} bytes.", MAXIMUM_DOCUMENT_LENGTH);
            }

            return contents;
        }
    }

    /**
     * Reads a decimal number of an answer.
     *
     * @return the number, or {@code -1} if the text is missing or no decimal number of at most nine digits
     */
    private static int parseNumber(@Nullable String text) {
        if (text == null || text.isEmpty() || text.length() > MAXIMUM_NUMBER_DIGITS || !text.chars().allMatch(Character::isDigit)) {
            return -1;
        }

        return Integer.parseInt(text);
    }

    private static @NotNull String escapeXml(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Reads the location of an SSDP search answer.
     *
     * @return the location, or {@code null} if the datagram is no positive search answer with a location
     */
    private static @Nullable String readLocation(@NotNull DatagramPacket packet) {
        String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r?\n");

        if (lines.length == 0 || !lines[0].startsWith(SEARCH_OK_PREFIX)) {
            return null;
        }

        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).startsWith(LOCATION_HEADER)) {
                return line.substring(LOCATION_HEADER.length()).strip();
            }
        }

        return null;
    }

    /**
     * Tells whether a URL is a plain HTTP URL at an address.
     */
    private static boolean isAt(@NotNull URI uri, @NotNull InetAddress address) {
        String host = uri.getHost();

        if (!HTTP_SCHEME.equalsIgnoreCase(uri.getScheme()) || host == null) {
            return false;
        }

        try {
            return InetAddress.ofLiteral(host).equals(address);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Tells whether no socket of this computer holds a UDP port: a socket on every local address can take it.
     */
    private static boolean isPortFree(int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            return socket.getLocalPort() == port;
        } catch (SocketException exception) {
            return false;
        }
    }


    private final @NotNull PortMappingConfiguration configuration;
    private final @NotNull Collection<@NotNull InetAddress> gateways;
    private final @NotNull HttpClient http;
    private final @NotNull Random random;

    private @Nullable InetAddress device;
    private @Nullable URI controlUrl;
    private @Nullable String serviceType;
    private @Nullable InetAddress localAddress;
    private @Nullable InetAddress externalAddress;
    private @NotNull String problem;

    /**
     * Creates a {@link UpnpClient} instance.
     *
     * @param configuration the search address, times and description
     * @param gateways      the gateway addresses, to which the client adds the address of a device that answers its search
     */
    UpnpClient(@NotNull PortMappingConfiguration configuration, @NotNull Collection<@NotNull InetAddress> gateways) {
        this.configuration = configuration;
        this.gateways      = gateways;
        this.http          = HttpClient.newBuilder()
                                       .version(HttpClient.Version.HTTP_1_1)
                                       .connectTimeout(Duration.ofMillis(HTTP_TIMEOUT_MILLIS))
                                       .followRedirects(HttpClient.Redirect.NEVER)
                                       .proxy(HttpClient.Builder.NO_PROXY)
                                       .build();
        this.random        = new SecureRandom();

        this.device          = null;
        this.controlUrl      = null;
        this.serviceType     = null;
        this.localAddress    = null;
        this.externalAddress = null;
        this.problem         = "no device";
    }

    /**
     * @return the protocol of this client
     */
    @Override
    public @NotNull MappingProtocol getProtocol() {
        return MappingProtocol.UPNP;
    }

    /**
     * @return why the protocol gave no mapping, such as {@code no device}
     */
    @Override
    public @NotNull String getProblem() {
        return this.problem;
    }

    /**
     * @return the external address the device reported to {@link #find()}, or {@code null} before a device answered
     */
    @Override
    public @Nullable InetAddress getExternalAddress() {
        return this.externalAddress;
    }

    /**
     * Searches the device with SSDP, reads its description and external address and removes the leftover mappings of this computer.
     *
     * @return {@code true} if a device with a WAN connection service and an external address answered, {@code false} otherwise
     */
    @Override
    public boolean find() {
        try (DatagramSocket socket = new DatagramSocket()) {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.configuration.getAnswerMillis());
            long resendNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(this.configuration.getAnswerMillis() / SEARCH_SENDS);
            Set<String> locations = new HashSet<>();
            byte[] buffer = new byte[MAXIMUM_SEARCH_ANSWER_LENGTH];
            int sends = 1;

            this.sendSearch(socket);

            while (true) {
                long nowNanos = System.nanoTime();

                if (nowNanos >= deadlineNanos) {
                    return false;
                }

                if (sends < SEARCH_SENDS && nowNanos >= resendNanos) {
                    this.sendSearch(socket);
                    sends++;
                }

                long untilNanos = sends < SEARCH_SENDS ? Math.min(resendNanos, deadlineNanos) : deadlineNanos;
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                socket.setSoTimeout((int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(untilNanos - nowNanos)));

                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException | PortUnreachableException exception) {
                    // No answer yet, or an earlier datagram reached a closed port: wait for the next one.
                    continue;
                }

                String location = readLocation(packet);
                InetAddress source = packet.getAddress();

                if (location != null && locations.add(location) && this.useDevice(location, source)) {
                    this.removeLeftovers();

                    return true;
                }
            }
        } catch (IOException exception) {
            this.problem = "failed: " + exception.getMessage();

            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.problem = "interrupted";

            return false;
        }
    }

    /**
     * Sends the search for each device type: to a multicast address on every local IPv4 interface that is up, to another address once.
     */
    private void sendSearch(@NotNull DatagramSocket socket) throws IOException {
        InetSocketAddress target = this.configuration.getSearchAddress();
        List<NetworkInterface> interfaces = new ArrayList<>();

        if (target.getAddress().isMulticastAddress()) {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (networkInterface.isUp() && !networkInterface.isLoopback() && networkInterface.supportsMulticast() &&
                    networkInterface.inetAddresses().anyMatch(address -> address instanceof Inet4Address)) {
                    interfaces.add(networkInterface);
                }
            }
        }

        for (String searchTarget : SEARCH_TARGETS) {
            byte[] request = ("M-SEARCH * HTTP/1.1\r\n" +
                              "HOST: " + target.getAddress().getHostAddress() + ":" + target.getPort() + "\r\n" +
                              "MAN: \"ssdp:discover\"\r\n" +
                              "MX: " + SEARCH_WAIT_SECONDS + "\r\n" +
                              "ST: " + searchTarget + "\r\n" +
                              "\r\n").getBytes(StandardCharsets.ISO_8859_1);

            if (interfaces.isEmpty()) {
                socket.send(new DatagramPacket(request, request.length, target));

                continue;
            }

            for (NetworkInterface networkInterface : interfaces) {
                try {
                    socket.setOption(StandardSocketOptions.IP_MULTICAST_IF, networkInterface);
                    socket.send(new DatagramPacket(request, request.length, target));
                } catch (IOException exception) {
                    LOGGER.debug("BEACON.LINK: no SSDP search on {0}: {1}", networkInterface.getName(), exception.getMessage());
                }
            }
        }
    }

    /**
     * Reads a device's description at the address that answered the search and chooses its WAN connection service with an external address.
     *
     * @return {@code true} if the device has one, {@code false} otherwise
     */
    private boolean useDevice(@NotNull String location, @NotNull InetAddress source) throws InterruptedException {
        URI description;

        try {
            description = new URI(location);
        } catch (URISyntaxException exception) {
            return false;
        }

        if (!isAt(description, source)) {
            return false;
        }

        this.gateways.add(source);

        Document document;

        try {
            HttpResponse<InputStream> response = this.http.send(HttpRequest.newBuilder(description)
                                                                           .timeout(Duration.ofMillis(HTTP_TIMEOUT_MILLIS))
                                                                           .GET()
                                                                           .build(), HttpResponse.BodyHandlers.ofInputStream());

            byte[] contents = readBody(response);

            if (response.statusCode() != HTTP_OK) {
                this.problem = "HTTP " + response.statusCode();

                return false;
            }

            document = parseDocument(contents);
        } catch (IOException | IllegalArgumentException exception) {
            this.problem = "no description: " + exception.getMessage();

            return false;
        }

        String base = readText(document.getDocumentElement(), "URLBase");
        URI baseUri = base == null || base.isEmpty() ? description : URI.create(base);
        NodeList services = document.getElementsByTagNameNS("*", "service");

        this.problem = "no WAN service";

        for (String type : SERVICE_TYPES) {
            for (int index = 0; index < services.getLength(); index++) {
                Node service = services.item(index);
                String serviceType = service instanceof Element element ? readText(element, "serviceType") : null;
                String control = serviceType != null && serviceType.equals(type) ? readText((Element) service, "controlURL") : null;

                if (control != null && this.useService(baseUri, control, type, source)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Takes a WAN connection service whose control URL is at the device's address and that reports an external IPv4 address.
     */
    private boolean useService(@NotNull URI base, @NotNull String control, @NotNull String type,
                               @NotNull InetAddress source) throws InterruptedException {
        URI resolved;

        try {
            resolved = base.resolve(control);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        if (!isAt(resolved, source)) {
            return false;
        }

        InetAddress local = GatewayDatagramUtils.findLocalAddress(new InetSocketAddress(source, Math.max(resolved.getPort(), 0)));

        if (!(local instanceof Inet4Address)) {
            return false;
        }

        this.controlUrl   = resolved;
        this.serviceType  = type;
        this.device       = source;
        this.localAddress = local;

        SoapAnswer answer = this.call("GetExternalIPAddress");
        String external = answer.readValue("NewExternalIPAddress");

        if (!answer.isSuccess() || external == null || external.isEmpty() || external.equals(NO_ADDRESS)) {
            this.problem = answer.isSuccess() ? "no external address" : answer.describe();

            return false;
        }

        try {
            this.externalAddress = InetAddress.ofLiteral(external);
        } catch (IllegalArgumentException exception) {
            this.problem = "invalid external address";

            return false;
        }

        return this.externalAddress instanceof Inet4Address;
    }

    /**
     * Removes the device's leftover mappings of this computer: UDP, to the local address, with the configured description, and with an
     * internal port no socket of this computer holds.
     */
    private void removeLeftovers() throws InterruptedException {
        InetAddress local = this.localAddress;
        List<Integer> leftovers = new ArrayList<>();

        if (local == null) {
            return;
        }

        for (int index = 0; index < MAXIMUM_LISTED_MAPPINGS; index++) {
            SoapAnswer entry = this.call("GetGenericPortMappingEntry", "NewPortMappingIndex", Integer.toString(index));

            if (!entry.isSuccess()) {
                break;
            }

            int externalPort = parseNumber(entry.readValue("NewExternalPort"));
            int internalPort = parseNumber(entry.readValue("NewInternalPort"));

            if (UDP_PROTOCOL.equalsIgnoreCase(entry.readValue("NewProtocol")) &&
                local.getHostAddress().equals(entry.readValue("NewInternalClient")) &&
                this.configuration.getDescription().equals(entry.readValue("NewPortMappingDescription")) &&
                externalPort > 0 && internalPort > 0 && isPortFree(internalPort)) {
                leftovers.add(externalPort);
            }
        }

        for (int externalPort : leftovers) {
            SoapAnswer answer = this.call("DeletePortMapping", "NewRemoteHost", ANY_REMOTE_HOST, "NewExternalPort", Integer.toString(externalPort),
                                          "NewProtocol", UDP_PROTOCOL);

            LOGGER.info("BEACON.LINK: port mapping leftover :{0}/UDP {1}", externalPort, answer.isSuccess() ? "removed" : answer.describe());
        }
    }

    /**
     * Asks the device found by {@link #find()} to map a local UDP port at the same external port; a conflict moves to another external port
     * (on version 2 the device chooses it), a device that takes only permanent leases is asked with lease {@code 0}.
     *
     * @param internalPort the local UDP port
     * @param leaseSeconds the lease to ask for
     * @return the granted mapping, or {@code null} if the device refused or did not answer
     */
    @Override
    public @Nullable PortMapping map(int internalPort, int leaseSeconds) {
        InetAddress gateway = this.device;
        InetAddress local = this.localAddress;
        InetAddress external = this.externalAddress;

        if (gateway == null || local == null || external == null) {
            return null;
        }

        int externalPort = internalPort;
        int lease = leaseSeconds;
        boolean any = false;

        try {
            for (int attempt = 0; attempt < MAXIMUM_ADD_ATTEMPTS; attempt++) {
                SoapAnswer answer = this.callAddMapping(any ? "AddAnyPortMapping" : "AddPortMapping", local, externalPort, internalPort, lease);
                int granted = any ? parseNumber(answer.readValue("NewReservedPort")) : externalPort;

                if (answer.isSuccess() && granted > 0) {
                    return new PortMapping(MappingProtocol.UPNP, gateway, new InetSocketAddress(external, granted), internalPort, lease);
                }

                if (answer.errorCode == ERROR_ONLY_PERMANENT_LEASES && lease != PERMANENT_LEASE) {
                    lease = PERMANENT_LEASE;
                } else if (answer.errorCode == ERROR_CONFLICT && !any) {
                    any          = VERSION_2_SERVICE_TYPE.equals(this.serviceType);
                    externalPort = any ? externalPort : DYNAMIC_PORT_MINIMUM + this.random.nextInt(DYNAMIC_PORT_COUNT);
                } else {
                    this.problem = answer.describe();

                    return null;
                }

                this.problem = answer.describe();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.problem = "interrupted";
        }

        return null;
    }

    /**
     * Asks the device to extend a mapping it granted with the same external port; a permanent mapping needs no renewal.
     *
     * @param mapping the mapping
     * @return the renewed mapping, or {@code null} if the device refused or did not answer
     */
    @Override
    public @Nullable PortMapping renew(@NotNull PortMapping mapping) {
        InetAddress local = this.localAddress;

        if (local == null) {
            return null;
        }

        if (mapping.isPermanent()) {
            return mapping;
        }

        try {
            SoapAnswer answer = this.callAddMapping("AddPortMapping", local, mapping.getExternalAddress().getPort(), mapping.getInternalPort(),
                                                    this.configuration.getLeaseSeconds());

            if (answer.isSuccess()) {
                return new PortMapping(MappingProtocol.UPNP, mapping.getGateway(), mapping.getExternalAddress(), mapping.getInternalPort(),
                                       this.configuration.getLeaseSeconds());
            }

            this.problem = answer.describe();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.problem = "interrupted";
        }

        return null;
    }

    /**
     * Asks the device to delete a mapping it granted ({@code DeletePortMapping}); a release that fails is logged.
     *
     * @param mapping the mapping
     */
    @Override
    public void release(@NotNull PortMapping mapping) {
        try {
            SoapAnswer answer = this.call("DeletePortMapping", "NewRemoteHost", ANY_REMOTE_HOST,
                                          "NewExternalPort", Integer.toString(mapping.getExternalAddress().getPort()), "NewProtocol", UDP_PROTOCOL);

            if (!answer.isSuccess()) {
                LOGGER.info("BEACON.LINK: port mapping release by UPnP failed: {0}", answer.describe());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.info("BEACON.LINK: port mapping release by UPnP interrupted");
        }
    }

    private @NotNull SoapAnswer callAddMapping(@NotNull String action, @NotNull InetAddress local, int externalPort, int internalPort,
                                               int leaseSeconds) throws InterruptedException {
        return this.call(action, "NewRemoteHost", ANY_REMOTE_HOST,
                         "NewExternalPort", Integer.toString(externalPort),
                         "NewProtocol", UDP_PROTOCOL,
                         "NewInternalPort", Integer.toString(internalPort),
                         "NewInternalClient", local.getHostAddress(),
                         "NewEnabled", ENABLED,
                         "NewPortMappingDescription", this.configuration.getDescription(),
                         "NewLeaseDuration", Integer.toString(leaseSeconds));
    }

    /**
     * Calls a SOAP action of the chosen service.
     *
     * @param action    the action name
     * @param arguments the argument names and values, alternating, in the order the action declares them
     * @return the answer; a request that fails has status {@code 0}
     */
    private @NotNull SoapAnswer call(@NotNull String action, @NotNull String @NotNull ... arguments) throws InterruptedException {
        URI control = this.controlUrl;
        String type = this.serviceType;

        if (control == null || type == null) {
            return new SoapAnswer(0, 0, null);
        }

        StringBuilder body = new StringBuilder();

        body.append("<?xml version=\"1.0\"?>\r\n")
            .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            .append("<u:").append(action).append(" xmlns:u=\"").append(type).append("\">");

        for (int index = 0; index + 1 < arguments.length; index += 2) {
            body.append('<').append(arguments[index]).append('>').append(escapeXml(arguments[index + 1]))
                .append("</").append(arguments[index]).append('>');
        }

        body.append("</u:").append(action).append("></s:Body></s:Envelope>\r\n");

        try {
            HttpResponse<InputStream> response = this.http.send(HttpRequest.newBuilder(control)
                                                                           .timeout(Duration.ofMillis(HTTP_TIMEOUT_MILLIS))
                                                                           .header("Content-Type", "text/xml; charset=\"utf-8\"")
                                                                           .header("SOAPAction", "\"" + type + "#" + action + "\"")
                                                                           .POST(HttpRequest.BodyPublishers.ofString(body.toString(),
                                                                                                                     StandardCharsets.UTF_8))
                                                                           .build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] contents = readBody(response);
            Document document = contents.length == 0 ? null : parseDocument(contents);
            String errorCode = document == null ? null : readText(document.getDocumentElement(), "errorCode");

            return new SoapAnswer(response.statusCode(), Math.max(parseNumber(errorCode), 0), document);
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.debug("BEACON.LINK: UPnP {0} failed: {1}", action, exception.getMessage());

            return new SoapAnswer(0, 0, null);
        }
    }

    /**
     * Frees the client's resources: stops its HTTP client.
     */
    @Override
    public void close() {
        this.http.shutdownNow();
    }
}
