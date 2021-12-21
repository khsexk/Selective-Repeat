import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.concurrent.Semaphore;
import java.util.HashMap;
import java.util.Random;

/*
 * 		2021년도 2학기 컴퓨터 네트워크
 * 		지도교수: 정의훈 교수님
 * 		과제 제출자: 2017154003 고현석
 */

public class UDPServer {

	static int timeOutDuration = 1000;
	static final String FileNotFoundMsg = "Error: File Not Found"; // File이 없을 때
	static final String OKMSG = "OK";
	static final int MTU = 5; // 한번에 처리될 트랜잭션 단위 = 5 Byte (MTU)

	static int PORT = 9999;	// Server Port Number
	static int pipeLine = 4;
	
	static int makingError = 1;
	static int windowMax = 3;
	static int sendNum = 0;

	static boolean[] ackPackets;	// 각 패킷에 대한 Index별 수신한 Ack 배열
	static int currentPackNo = 0;	// next packet to be sent
	static HashMap<Integer, Thread> hashTimers; // 패킷의 순서번호에 따른 타이머 해쉬맵
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
		DatagramSocket udpServer;
		DatagramPacket dataPacket;
		
		try {
			printBanner();
			
			/* Server Open */
			udpServer = new DatagramSocket(PORT);
			System.out.println("                                         🖥 UDP Server Starts!");
			
			boolean gns = true;
			while(gns==true) {
				/* Client의 접속을 기다리는중 */
				System.out.println("                                         Waiting for Client Request ...\n");
				
				/* 접속한 Client가 보낸 File 명을 DatagramPacket을 통해 recv */
				dataPacket = new DatagramPacket(buf, buf.length);
				udpServer.receive(dataPacket);

				/* 접속한 Client의 주소, 포트와 요청한 파일 이름 출력 */
				String filename = new String(dataPacket.getData(), 0, dataPacket.getLength());
				
				sb = new StringBuilder();
				sb.append("                                         ┌───────── Client Info ──────────┐\n");
				sb.append("                                         │────────────────────────────────│\n");
				sb.append("                                         │───────Address: ").append(dataPacket.getAddress()).append("──────│\n");
				sb.append("                                         │───────────Port: ").append(dataPacket.getPort()).append("──────────│\n");
				sb.append("                                         │───────File Name: ").append(filename).append("──────│");
				System.out.println(sb.toString());

				/* 요청한 File이 있는지 탐색 후 File 사이즈 출력 및 데이터 패킷 전송 시작 */
				try {
					File file = new File(filename);
					fileContent = Files.readAllBytes(file.toPath());
					System.out.println("                                         │───────File size: " + fileContent.length + " Byte───────│");
					System.out.println("                                         └────────────────────────────────┘\n");
					System.out.println("                                         **********************************");
					System.out.println("                                         **********************************");
					System.out.println("                                                       전송시작!             ");
					System.out.println("                                         **********************************");
					System.out.println("                                         **********************************\n");
					System.out.println("                                         >>> 초기 버퍼");
					printBuf(0);
				} catch (FileNotFoundException e) {
					sendMsgToClient(FileNotFoundMsg, udpServer, dataPacket);
					continue;
				}

				/* Selective Repeat 접근 방식을 사용하여 File 전송 */
				selctiveRepeatARQ(fileContent, dataPacket, udpServer);
				gns = false;
				System.out.println("서버를 종료합니다");
			}	// while
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	
	/*
	 * 		↑  Main and Global Variable Part
	 * 
	 * 		↓  Method Part
	*/
	
	
	/* 프로그램 시작 전, 배너 출력 Method */
	public static void printBanner() {
		System.out.println("\n");
		System.out.println("                                         ┌********************************┐");
		System.out.println("                                         │*** COMPUTER NETWORK PROJECT ***│");
		System.out.println("                                         │********************************│");
		System.out.println("                                         │────────────────────────────────│");
		System.out.println("                                         │───── Computer Engineering ─────│");
		System.out.println("                                         │──── 2017154003 Hyunseok Ko ────│");
		System.out.println("                                         │───── SELECTIVE REPEAT ARQ ─────│");
		System.out.println("                                         │────────────────────────────────│");
		System.out.println("                                         **********************************");
		System.out.println("                                         **********************************\n");
	}
	
	/* Client에게 Message 전송 */
	public static void sendMsgToClient(String s, DatagramSocket socket, DatagramPacket datapacket) {
		/* String형의 메세지를 byte형식으로 바꿔서 byte 배열에 저장 */
		byte[] buf = new byte[1000];
		buf = s.getBytes();
		
		DatagramPacket out = new DatagramPacket(buf, buf.length, datapacket.getAddress(), datapacket.getPort());
		
		try {
			socket.send(out);
		} catch (IOException e) {
			System.out.println("  메세지 전송 실패");
			e.printStackTrace();
		}
	}

	/* selective repeat 접근 방식 */
	public static void selctiveRepeatARQ(byte[] fileContent, DatagramPacket dgp, DatagramSocket sk) {
		numberOfPackets = (int) Math.ceil(fileContent.length / MTU);	// 패킷의 갯수 결정
		ackPackets = new boolean[numberOfPackets];	// 패킷의 갯수만큼 Ack 배열 생성
		currentPackNo = 0;	// 최근에 전송한 패킷 번호
		hashTimers = new HashMap<Integer, Thread>();	// Timer 생성

		/* 패킷의 총 갯수와 파일을 찾았고 전송을 시작하겠다는 OK 메시지 전송 */
		sendMsgToClient(OKMSG + " " + numberOfPackets, sk, dgp);

		// 첫 번째(0) 패킷 전송
		sendNewPackets(dgp, sk);

		while (true) {
			AckPacket packet = getAck(sk);
			System.out.println("                                         Ack recieved");

			// ACK 수신
			try {
				mutex.acquire();

				System.out.println("                                         <<<<<<< new ack: " + packet.ackno);
				saveAck(packet.ackno);
					
				int dynamicWindow = windowControl(ackPackets);
				if (dynamicWindow > 4) { 
					printBuf(5); 
					windowMax = 8;
				} 
				else { 
					printBuf(dynamicWindow);
					windowMax = dynamicWindow + 3;
				}

				sendNewPackets(dgp, sk);
				
				/* 모든 패킷에 대한 ACK를 받았을 때 타이머 모두 제거 */
				if (windowControl(ackPackets) == 9) {
					System.out.println("\n파일 전송이 완료되어 남은 타이머를 모두 제거합니다.");
					killTimers();
					return;
				}
				
				mutex.release();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}	// while
	}
	
	/* 파이프라인 방식으로 패킷 전송 메서드 */
	public static void sendNewPackets(DatagramPacket dataPacket, DatagramSocket socket) {
		for (int i = 0; i < pipeLine; i++) {
			/* 보낼 패킷의 순서번호가 패킷의 수 이상일 때 모두 전송된 것이므로 return */
			if (currentPackNo >= numberOfPackets)	
				return;
			
			/* Server의 sender window가 가득 찬 경우 */
			if(sendNum == 4) 
				return;
			
			/* Server의 sender window 밖의 패킷 전송 제한 */
			if(currentPackNo > windowMax)
				return;
			
			/* 패킷 매핑 메서드 호출 */
			System.out.println(">>>>>>> Send Packet: "+ currentPackNo);
			sendPacket(dataPacket, socket, currentPackNo);

			/* Timer 객체를 상속받아 만든 Timer를 패킷 순서번호에 따라 생성하고, 해쉬에 삽입 */
			Timer timer = new Timer(currentPackNo, dataPacket, socket, timeOutDuration);
			hashTimers.put(currentPackNo, timer);
			
			/* 타이머 시작 */
			timer.start();
			sendNum++;
			// 패킷을 선송했으므로 패킷 순서번호 1만큼 증가
			currentPackNo++;
		}
	}
	
	/* 패킷과 순서번호로 Packet 생성 및 send()를 포함한 메서드 sendObjectToClient() 호출 */
	public static void sendPacket(DatagramPacket dataPacket, DatagramSocket socket, int packetNo) {
		int size;
		
		/* 마지막 패킷일 경우 if문, 아닐 경우 else문에 들어가 전송할 패킷의 size 결정 */
		if (packetNo * MTU + MTU > fileContent.length)
			size = fileContent.length - packetNo * MTU;
		else
			size = MTU;
		
		
		System.out.println("→→→→→ From " + (packetNo * MTU) + "byte To " + (packetNo * MTU + size) + "byte\n");
		
		/* 전송할 패킷을 byte 배열에 저장 */
		byte[] part = new byte[size];
		System.arraycopy(fileContent, packetNo * MTU, part, 0, size);

		DataPacket packet = new DataPacket(part, size, packetNo);
		
		
		
		/* lossPacket() 메서드를 통해 20% 확률로 패킷 손실 발생 → Server는 모름 */
		/* if (lossPacket()) */
		if(packetNo != 2 || makingError==0) {
			sendObjectToClient(packet, dataPacket.getAddress(), dataPacket.getPort(), socket);
		}
		else {
			System.out.println("                                         "
					+ "                                         Warning: "+packetNo + "번 패킷 손실");
			makingError = 0;
		}
	}
	
	/* 손실이 일어날 확률 결정 메서드 */
	private static boolean lossPacket() {
		int n = rand.nextInt(10); // 0 ≤ n ≤ 9

		// 20% 확률로 손실 발생
		if (n > 1)
			return true;

		return false;
	}
	
	/* 패킷 전송 메서드 */
	public static void sendObjectToClient(Object o, InetAddress address, int desPort, DatagramSocket dataSocket) {
		try {
			/* 패킷 데이터 직렬화를 위해 ByteArrayOutputStream과 ObjectOutputStream 사용 */
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream(5000);
			ObjectOutputStream os = new ObjectOutputStream(new BufferedOutputStream(byteStream));
			
			os.flush();
			os.writeObject(o);
			os.flush();
			
			/* ByteArrayOutputStream을 Byte 배열로 변환하고, 배열을 패킷에 담음 
			 * 패킷 전송
			 */
			byte[] sendBuf = byteStream.toByteArray();
			DatagramPacket packet = new DatagramPacket(sendBuf, sendBuf.length, address, desPort);
			dataSocket.send(packet);
			
			os.close();
		} catch (UnknownHostException e) {
			System.err.println("  Exception:  " + e);
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/* 누적 ACK에 대한 ACK 배열 메서드 */
	private static void saveAck(int ackno) {
		ackPackets[ackno] = true;
		
		// kill its timer
		Thread t = hashTimers.remove(ackno);
		t.interrupt();
	}
	
	/* 전송 후 남아있는 Timer를 모두 제거하는 메서드 */
	private static void killTimers() {
		for (Integer x : hashTimers.keySet()) {
			hashTimers.get(x).interrupt();
		}
	}

	/* TimeOut 발생 */
	public static void handleTimeOut(int packetNo, DatagramPacket dgp, DatagramSocket sk) {
		try {
			System.out.println("                                         "
					+ "                                         Warning: " + packetNo + "번 타이머 TimeOut");
			
			mutex.acquire();

			System.out.println(">>>>>>> Re-Send Packet: " + packetNo);
			System.out.println(">>>>>>> 이유: TimeOut");
			sendPacket(dgp, sk, packetNo);

			mutex.release();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/* ACK 패킷을 받는 메서드 */
	public static AckPacket getAck(DatagramSocket dSock) {
		Object recievedObj = recvAckFromClient(dSock);

		if (recievedObj != null) {
			try {
				AckPacket ack = (AckPacket) recievedObj;
				sendNum--;
				return ack;
			} catch (Exception e) {
				;
			}
		}
		return null;
	}
	
	/* Client로부터 ACK 패킷을 받는 메서드 */
	public static Object recvAckFromClient(DatagramSocket dSock) {
		try {
			/* byte 배열을 패킷과 매핑하여 recv */
			byte[] recvBuf = new byte[5000];
			DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
			dSock.receive(packet);
			
			/* 받은 패킷을 스트림을 이용하여 Object로 변환 후 리턴 */
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
	
	public static int windowControl(boolean[] ackPackets) {
		int num = 0;
		
		for(int i=0 ; i<ackPackets.length ; i++) {
			if(ackPackets[i] == false) { 
				return num; 
			}
			else { 
				num++; 
			}
		}
		return num;
	}
	
	
	
	/*
	 *  UI 관련 메서드
	 *  윈도우 크기는 4로 가정 ( 패킷 순서번호(8) / 2 ) 
	 */
	static void printBuf(int base) {
		sb = new StringBuilder();
		
		sb.append("                                         ┌───────────────────────────────────┐\n")
		  .append("                                         │ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │\n")
		  .append("                                         └───────────────────────────────────┘\n");
		      
		if(base > 0) {
			for(int i=0; i<base; i++) 
				sb.append("    ");
		}
		sb.append("                                         └───────────────┘\n");
		System.out.println(sb.toString());
	}
}