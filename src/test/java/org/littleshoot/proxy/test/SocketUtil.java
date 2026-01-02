package org.littleshoot.proxy.test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Random;

public class SocketUtil {

    private static final Random random = new Random();

    private SocketUtil(){}

    public static int getRandomPort() {
        int low = 49152;
        int high = 65535;
        return random.nextInt(high - low) + low;
    }

    public static int getRandomAvailablePort() {
        int randomPort = getRandomPort();
        while (available(randomPort)) {
            randomPort = getRandomPort();
        }
        return randomPort;
    }


    public static boolean available(String host, int port) {
        try (Socket s = new Socket(host, port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean available(int port) {
        return available(getIP(), port);
    }

    public static String getIP() {
        try (DatagramSocket datagramSocket = new DatagramSocket()) {
            datagramSocket.connect(InetAddress.getByName("8.8.8.8"), 12345);
            return datagramSocket.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}

