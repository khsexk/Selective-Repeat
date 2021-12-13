import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.concurrent.Semaphore;
import java.util.Hashtable;
import java.util.Random;

public class UDPServer {

	static int timeOutDuration = 1000;
	static final String FileNotFoundMsg = "Error: File Not Found"; // File이 없을 때
	static final String OKMessage = "OK";
	static final int chunkSize = 5; // 한번에 처리될 트랜잭션 단위 = 500 Byte (MSS)

	// default configuration value
	static int PORT = 9999;	// Server Port Number
	static int cwnd = 1;
	static int dupACKcount = 0;	// 중복 ACK
	static int ssthreash = 5;

	static boolean ackPackets[];
	static int currentPackNo = 0; // next packet to be sent
	static Hashtable<Integer, Thread> hashTimers; // save active timers
	static byte[] fileContent;	// Client 요청에 따라 송신할 File
	static int numberOfPackets;	// 패킷 수
	static Semaphore mutex;		// Mutex 세마포어

	static Random rand = new Random();	// 랜덤 숫자 생성 객체
	static StringBuilder sb;	/* 문자열 조작을 위한 StringBuilder 
								  (멀티스레드가 아니므로 StringBuilder 사용) */

	public static void main(String[] args) {
		/* 공유 자원 동시 접속에 의해 발생되는 교착 상태 회피 */
		mutex = new Semaphore(1);	
		
		/* byte단위로 데이터 통신을 하기에 byte 배열 선언 */
		byte[] buf = new byte[1000];
		
		/* UDP 소켓과 패킷 생성 (초기화X) */
		DatagramSocket udpSocket;
		DatagramPacket dataPacket;
		
		try {
			printBanner();
			
			/* Server Open */
			udpSocket = new DatagramSocket(PORT);
			System.out.println("  🖥 UDP Server Starts!");
			
			while(true) {
				/* Client의 접속을 기다리는중 */
				System.out.println("  Waiting for Client Request ...");
				
				/* 접속한 Client가 보낸 File 명을 DatagramPacket을 통해 recv */
				dataPacket = new DatagramPacket(buf, buf.length);
				udpSocket.receive(dataPacket);

				/* 접속한 Client의 주소, 포트와 요청한 파일 이름 출력 */
				String filename = new String(dataPacket.getData(), 0, dataPacket.getLength());
				
				sb = new StringBuilder();
				sb.append("┌───────── Client Info ──────────┐\n");
				sb.append("│────────────────────────────────│\n");
				sb.append("│───────Address: ").append(dataPacket.getAddress()).append("──────│\n");
				sb.append("│───────────Port: ").append(dataPacket.getPort()).append("──────────│\n");
				sb.append("│───────File Name: ").append(filename).append("──────│");
				System.out.println(sb.toString());

				/* 요청한 File이 있는지 탐색 후 File 사이즈 출력 및 데이터 패킷 전송 시작 */
				try {
					File file = new File(filename);
					fileContent = Files.readAllBytes(file.toPath());
					System.out.println("│───────File size: " + fileContent.length + " Byte───────│");
					System.out.println("└────────────────────────────────┘\n");
				} catch (FileNotFoundException e) {
					sendMsgToClient(FileNotFoundMsg, udpSocket, dataPacket);
					continue;
				}

				/* send file using stop and wait strategy */
				sendFileToClient(fileContent, dataPacket, udpSocket);

				System.out.println("  file is sent");
			}	// while
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/* 프로그램 시작 전, 배너 출력 Method */
	public static void printBanner() {
		System.out.println("\n");
		System.out.println("┌********************************┐");
		System.out.println("│*** COMPUTER NETWORK PROJECT ***│");
		System.out.println("│********************************│");
		System.out.println("│────────────────────────────────│");
		System.out.println("│───── Computer Engineering ─────│");
		System.out.println("│──── 2017154003 Hyunseok Ko ────│");
		System.out.println("│───── SELECTIVE REPEAT ARQ ─────│");
		System.out.println("│────────────────────────────────│");
		System.out.println("**********************************");
		System.out.println("**********************************\n");
	}
	
	/* 손실이 일어날 확률 결정 Method */
	private static boolean lossSim() {
		int n = rand.nextInt(10); // n ==> 0...9

		// 30% 확률로 손실 발생
		if (n > 2)
			return true;

		return false;
	}

	/* selective repeat 접근 방식을 사용하여 File 전송 Method */
	public static void sendFileToClient(byte[] fileContent, DatagramPacket dgp, DatagramSocket sk) {
		numberOfPackets = (int) Math.ceil(fileContent.length / chunkSize);	// 패킷의 갯수 결정
		ackPackets = new boolean[numberOfPackets];	// 패킷의 갯수만큼 Ack 배열 생성
		cwnd = 1;
		currentPackNo = 0;	// 최근에 전송한 패킷 번호
		ssthreash = chunkSize;
		hashTimers = new Hashtable<Integer, Thread>();

		// send ok message with number of packets
		sendMsgToClient(OKMessage + " " + numberOfPackets, sk, dgp);

		// send packet no. 1
		sendNewPackets(dgp, sk);

		while (true) {
			System.out.println("  waiting for ack");
			AckPacket packet = getAck(sk);
			System.out.println("  Ack recieved");

			// stoping condition
			if (packet.ackno == numberOfPackets - 1) {
				killTimers();
				return;
			}

			// ack is received
			try {
				mutex.acquire();
				// System.out.println("acqiure mutex: ");

				// check if duplicate
				if (isDuplicateAck(packet)) {
					dupACKcount++;
					System.out.println("  duplicat ack: " + packet.ackno);
					if (dupACKcount == 3) {
						ssthreash = cwnd / 2;
						cwnd = ssthreash + 3;
						sendMissingPacket(dgp, sk, packet.ackno);
					} else if (dupACKcount > 3) {
						cwnd++;
						sendNewPackets(dgp, sk);
					}
				} else {
					System.out.println("  new ack: " + packet.ackno);
					// new ack

					dupACKcount = 0;

					ackAllPacketsBefore(packet.ackno);

					if (dupACKcount >= 3) {
						cwnd = ssthreash;
					} else {
						cwnd++;
						sendNewPackets(dgp, sk);
					}
				}

				mutex.release();
				// System.out.println("release mutex: ");
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private static void ackAllPacketsBefore(int ackno) {
		int i = ackno;

		while ( i >= 0 && !ackPackets[i]) {
			ackPackets[i] = true;
			// kill its timer
			Thread t = hashTimers.remove(i);
			t.interrupt();
			i--;
		}
	}

	private static void sendMissingPacket(DatagramPacket dgp, DatagramSocket sk, int ackno) {
		hashTimers.remove(ackno + 1).interrupt();

		Timer timer = new Timer(ackno + 1, dgp, sk, timeOutDuration);
		hashTimers.put(ackno + 1, timer);
		sendPacket(dgp, sk, ackno + 1);
		timer.start();
	}

	private static void killTimers() {
		for (Integer x : hashTimers.keySet()) {
			hashTimers.get(x).interrupt();
		}
	}

	public static void handleTimeOut(int packetNo, DatagramPacket dgp, DatagramSocket sk) {

		try {
			System.out.println("  handle time out: " + packetNo);
			mutex.acquire();

			// System.out.println("acquire mutex timeout packet: " + packetNo);
			// using sophomore
			ssthreash = cwnd / 2;
			cwnd = 1;
			dupACKcount = 0;

			sendPacket(dgp, sk, packetNo);

			mutex.release();
			// System.out.println("release mutex timeout packet: " + packetNo);

		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	// send packet with packetNo
	public static void sendPacket(DatagramPacket dgp, DatagramSocket sk, int packetNo) {
		int size;
		if (packetNo * chunkSize + chunkSize > fileContent.length)
			size = fileContent.length - packetNo * chunkSize;
		else
			size = chunkSize;

		System.out.printf("  send %d from %d, size %d\n", packetNo, packetNo * chunkSize, size);

		byte[] part = new byte[size];
		System.arraycopy(fileContent, packetNo * chunkSize, part, 0, size);

		DataPacket packet = new DataPacket(part, size, packetNo);

		if (lossSim())
			sendObjectToClient(packet, dgp.getAddress(), dgp.getPort(), sk);
		else
			System.out.println("  packet is not sent: " + packetNo);
	}

	// send cwnd packets where
	public static void sendNewPackets(DatagramPacket dgp, DatagramSocket sk) {

		for (int i = 0; i < cwnd; i++) {

			if (currentPackNo >= numberOfPackets)
				return;

			sendPacket(dgp, sk, currentPackNo);

			// create thread timeout and put in hashtable
			Timer timer = new Timer(currentPackNo, dgp, sk, timeOutDuration);
			hashTimers.put(currentPackNo, timer);
			timer.start();

			// increase currentPacketNo
			currentPackNo++;
		}
	}

	// wait for ack
	// it is a blocking function
	public static AckPacket getAck(DatagramSocket dSock) {
		Object recievedObj = recvObjFrom(dSock);

		if (recievedObj != null) {
			try {
				AckPacket ack = (AckPacket) recievedObj;
				return ack;
			} catch (Exception e) {
			}
		}
		return null;
	}

	public static boolean isDuplicateAck(AckPacket ackPacket) {
		if (ackPackets[ackPacket.ackno])
			return true;
		return false;
	}

	public static Object recvObjFrom(DatagramSocket dSock) {
		try {
			// DatagramSocket dSock = new DatagramSocket(PORT);
			byte[] recvBuf = new byte[5000];
			DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
			dSock.receive(packet);
			int byteCount = packet.getLength();
			ByteArrayInputStream byteStream = new ByteArrayInputStream(recvBuf);
			ObjectInputStream is = new ObjectInputStream(new BufferedInputStream(byteStream));
			Object o = is.readObject();
			is.close();
			return (o);
		} catch (SocketTimeoutException e) {
			// timeout exception.
			System.out.println("  Timeout reached!!! " + e);
		} catch (IOException e) {
			System.err.println("  Exception:  " + e);
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		return (null);
	}
	
	// Client에게 Message 전송
	public static void sendMsgToClient(String s, DatagramSocket sk, DatagramPacket dgp) {
		byte[] buf = new byte[1000];
		buf = s.getBytes();
		DatagramPacket out = new DatagramPacket(buf, buf.length, dgp.getAddress(), dgp.getPort());
		try {
			sk.send(out);
		} catch (IOException e) {
			System.out.println("  Cannot send response to client");
			e.printStackTrace();
		}
	}

	public static void scanAndSend() {
		byte[] buf = new byte[1000];
		DatagramPacket dgp = new DatagramPacket(buf, buf.length);
		DatagramSocket sk;

		try {
			sk = new DatagramSocket(PORT);
			System.out.println("  Server started");
			while (true) {
				sk.receive(dgp);
				String rcvd = new String(dgp.getData(), 0, dgp.getLength()) + ", from address: " + dgp.getAddress()
						+ ", port: " + dgp.getPort();
				System.out.println(rcvd);

				BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
				String outMessage = stdin.readLine();
				buf = ("Server say: " + outMessage).getBytes();
				DatagramPacket out = new DatagramPacket(buf, buf.length, dgp.getAddress(), dgp.getPort());
				sk.send(out);
			}
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	public static void sendObjectToClient(Object o, InetAddress address, int desPort, DatagramSocket dSock) {
		try {
			// DatagramSocket dSock = new DatagramSocket(PORT);
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream(5000);
			ObjectOutputStream os = new ObjectOutputStream(new BufferedOutputStream(byteStream));
			os.flush();
			os.writeObject(o);
			os.flush();
			// retrieves byte array
			byte[] sendBuf = byteStream.toByteArray();
			DatagramPacket packet = new DatagramPacket(sendBuf, sendBuf.length, address, desPort);
			int byteCount = packet.getLength();
			dSock.send(packet);
			os.close();
		} catch (UnknownHostException e) {
			System.err.println("  Exception:  " + e);
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}