package org.dsa.iot.broker.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;
import org.bouncycastle.jcajce.provider.digest.SHA256;
import org.dsa.iot.broker.Broker;
import org.dsa.iot.dslink.handshake.LocalKeys;
import org.dsa.iot.dslink.handshake.RemoteKey;
import org.dsa.iot.dslink.util.UrlBase64;
import org.dsa.iot.dslink.util.json.JsonObject;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * @author Samuel Grenier
 */
public class Client extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String pubKeyHash;
    private final String dsId;
    private final String name;

    private ChannelHandlerContext ctx;
    private ClientManager manager;

    private String publicKey;
    private boolean isRequester;
    private boolean isResponder;
    private JsonObject linkData;
    private String dsaVersion;

    private String salt;
    private byte[] sharedSecret;
    private LocalKeys tempKey;
    private String path;

    private Client(String dsId) {
        if (dsId == null) {
            throw new NullPointerException("dsId");
        }
        this.dsId = dsId;
        this.pubKeyHash = dsId.substring(dsId.length() - 43);
        this.name = dsIdToName(dsId, pubKeyHash.length());
    }

    public String getDsId() {
        return dsId;
    }

    public String getPubKeyHash() {
        return pubKeyHash;
    }

    public String getName() {
        return name;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    public void setPublicKey(String key) {
        this.publicKey = key;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setRequester(Boolean requester) {
        this.isRequester = requester != null ? requester : false;
    }

    public boolean isRequester() {
        return isRequester;
    }

    public void setResponder(Boolean responder) {
        this.isResponder = responder != null ? responder : false;
    }

    public boolean isResponder() {
        return isResponder;
    }

    public void setLinkData(JsonObject data) {
        this.linkData = data;
    }

    public JsonObject getLinkData() {
        return linkData;
    }

    public void setDsaVersion(String version) {
        this.dsaVersion = version;
    }

    public String getDsaVersion() {
        return dsaVersion;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public String getSalt() {
        return salt;
    }

    public void setSharedSecret(byte[] secret) {
        this.sharedSecret = secret != null ? secret.clone() : null;
    }

    public byte[] getSharedSecret() {
        return sharedSecret != null ? sharedSecret.clone() : null;
    }

    public void setTempKey(LocalKeys keys) {
        this.tempKey = keys;
    }

    public LocalKeys getTempKey() {
        return tempKey;
    }

    public void setManager(ClientManager manager) {
        this.manager = manager;
    }

    public ClientManager getManager() {
        return manager;
    }

    public boolean validate(String auth) {
        if (auth == null) {
            return false;
        }
        byte[] origHash = UrlBase64.decode(auth);
        byte[] validHash;
        {
            byte[] salt = getSalt().getBytes(CharsetUtil.UTF_8);
            byte[] ss = getSharedSecret();
            byte[] bytes = new byte[salt.length + ss.length];
            System.arraycopy(salt, 0, bytes, 0, salt.length);
            System.arraycopy(ss, 0, bytes, salt.length, ss.length);

            SHA256.Digest sha = new SHA256.Digest();
            validHash = sha.digest(bytes);
        }
        return MessageDigest.isEqual(origHash, validHash);
    }

    public static Client registerNewConn(Broker broker,
                                         String dsId,
                                         JsonObject handshake) {
        Client client = new Client(dsId);
        client.setManager(broker.getClientManager());
        client.setPublicKey((String) handshake.get("publicKey"));
        client.setRequester((Boolean) handshake.get("isRequester"));
        client.setResponder((Boolean) handshake.get("isResponder"));
        client.setLinkData((JsonObject) handshake.get("linkData"));
        client.setDsaVersion((String) handshake.get("version"));
        client.setSalt(generateSalt());

        String path = broker.getNodeTree().addOfflineDSLink(client);
        client.setPath(path);

        ClientManager manager = broker.getClientManager();
        manager.clientConnecting(client);

        LocalKeys keys = LocalKeys.generate();
        client.setTempKey(keys);

        RemoteKey remKey = RemoteKey.generate(keys, client.getPublicKey());
        client.setSharedSecret(remKey.getSharedSecret());

        return client;
    }

    private static String dsIdToName(String dsId, int hashLength) {
        String tmp = dsId.substring(0, hashLength);
        if (tmp.lastIndexOf('-') > -1) {
            tmp = tmp.substring(0, tmp.length() - 1);
        }
        if (tmp.isEmpty()) {
            throw new IllegalStateException("Invalid DsId name from " + dsId);
        }
        return tmp;
    }

    private static String generateSalt() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return new String(b, CharsetUtil.UTF_8);
    }

    public void close() {
        if (ctx != null) {
            ctx.close();
            ctx = null;
        }
        getManager().clientDisconnected(this);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        close();
    }

    @Override
    protected void messageReceived(ChannelHandlerContext ctx,
                                   WebSocketFrame frame) throws Exception {
        final Channel channel = ctx.channel();
        if (frame instanceof TextWebSocketFrame) {
            String data = ((TextWebSocketFrame) frame).text();
            channel.writeAndFlush(data); // echo it out for now
        } else if (frame instanceof PingWebSocketFrame) {
            ByteBuf buf = frame.content().retain();
            channel.writeAndFlush(new PongWebSocketFrame(buf));
        } else if (frame instanceof CloseWebSocketFrame) {
            ChannelPromise prom = channel.newPromise();
            ChannelFutureListener cfl = ChannelFutureListener.CLOSE;
            channel.writeAndFlush(frame.retain(), prom).addListener(cfl);
        } else {
            String err = "%s frame types not supported";
            err = String.format(err, frame.getClass().getName());
            throw new UnsupportedOperationException(err);
        }
    }
}