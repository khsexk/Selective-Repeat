import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Hashtable;

public class UDPClient {

	static final int PORT = 9999;
	static final String FileNotFoundMSG = "Error: File Not Found";
	static final String OKMSG = "OK";
	
	static int base = 0;
	static HashMap<Integer, DataPacket> hashPackets;

	public static void main(String[] args) {

		try {
			DatagramSocket udpSocket = new DatagramSocket();
			System.out.println("UDP Client 소켓이 생성되었습니다");

			byte[] buf = new byte[1000];
			DatagramPacket dataPacket = new DatagramPacket(buf, buf.length);

			InetAddress hostAddress = InetAddress.getByName("localhost");
			
			/* File Name 입력*/
			BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
			System.out.print("Please enter file name: ");
			String filename = stdin.readLine();

			String outString = filename;
			buf = outString.getBytes();

			/* File Name 서버로 전송 */
			DatagramPacket out = new DatagramPacket(buf, buf.length, hostAddress, PORT);
			udpSocket.send(out);

			/* 서버로부터 MSG 받기 */
			udpSocket.receive(dataPacket);
			String rcvd = new String(dataPacket.getData(), 0, dataPacket.getLength());

			/* if) 서버에서 요청한 File을 찾지 못했을 때 */
			if (rcvd.equals(FileNotFoundMSG)) {
				System.out.println("요청한 파일을 서버에서 찾지 못했습니다");
			}	// if
			/* else) 서버에서 요청한 File을 찾았을 때 */
			else {
				System.out.println("서버에서 요청한 파일 전송을 시작합니다");
				String[] parts = rcvd.split(" ");	// 0: OKMSG, 1: 패킷의 총 갯수
				
				/* OKMSG를 포함해 메시지가 제대로 도착했을 때 */
				if (parts.length == 2 && parts[0].equals(OKMSG)) {
					int numberOfPackets = Integer.valueOf(parts[1]);
					System.out.println(">>> 초기 버퍼");
					printBuf(base);
					/* 서버로부터 File recv 시작 */
					receiveFileFromSrever(filename, numberOfPackets, udpSocket, out);
				} 
				/* 메세지를 인식하지 못할 떄 */
				else {
					System.out.println("Server Message is not recognized: " + rcvd);
				}
				
				System.out.println("File을 모두 수신했습니다.");
				System.out.println("서버와의 연결을 종료합니다.");
			}	// else
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	public static void receiveFileFromSrever(String filename, int numberOfPackets, DatagramSocket udpSocket, DatagramPacket dgp) {
		/* 서버로부터 받을 패킷의 순서번호에 따라 저장할 HashMap 초기화 */
		hashPackets = new HashMap<Integer, DataPacket>();

		try {
			FileOutputStream fos = new FileOutputStream(filename);

			int currentPacket = 0;	// 받아야 할 패킷 순서번호
			DataPacket packet;
			AckPacket ackPacket;

			while (currentPacket < numberOfPackets) {
				if (hashPackets.containsKey(currentPacket)) {
					packet = hashPackets.remove(currentPacket++);
					fos.write(packet.data);
				} 
				else {
					/* ACK 전송 */
					if (currentPacket != 0) {
						System.out.println("send ack: " + (currentPacket - 1));
						ackPacket = new AckPacket(0, currentPacket - 1);
						sendObjectToServer(ackPacket, dgp.getAddress(), dgp.getPort(), udpSocket);
						
						/* UI 출력 파트 */
						if(currentPacket > 4) {
							printBuf(5);
						} else {
							printBuf(currentPacket);
						}
						
					}
					/* 서버로부터 패킷 recv */
					packet = (DataPacket) recvObjFrom(udpSocket); 
					System.out.println("recieved packet: " + packet.seqno);
					
					if (packet.seqno == (currentPacket)) {
						currentPacket++;
						fos.write(packet.data);
					} 
					else {
						if (!hashPackets.containsKey(packet.seqno) && packet.seqno > currentPacket)
							hashPackets.put(packet.seqno, packet);
					}
				}
			}	// while
			fos.close();
			// send ack of last packet
			System.out.println("send ack: " + (numberOfPackets - 1));
			printBuf(5);
			ackPacket = new AckPacket(0, numberOfPackets - 1);
			sendObjectToServer(ackPacket, dgp.getAddress(), dgp.getPort(), udpSocket);

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	public static void sendObjectToServer(Object o, InetAddress address, int desPort, DatagramSocket dSock) {
		try {
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
			System.err.println("Exception:  " + e);
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void recieveAndSend() {
		try {
			DatagramSocket s = new DatagramSocket();

			byte[] buf = new byte[1000];
			DatagramPacket dp = new DatagramPacket(buf, buf.length);

			InetAddress hostAddress = InetAddress.getByName("localhost");
			while (true) {
				BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
				String outMessage = stdin.readLine();

				if (outMessage.equals("bye"))
					break;
				String outString = "Client say: " + outMessage;
				buf = outString.getBytes();

				DatagramPacket out = new DatagramPacket(buf, buf.length, hostAddress, PORT);
				s.send(out);

				s.receive(dp);
				String rcvd = "rcvd from " + dp.getAddress() + ", " + dp.getPort() + ": "
						+ new String(dp.getData(), 0, dp.getLength());
				System.out.println(rcvd);
			}

		} catch (SocketException e) {
			e.printStackTrace();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

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
		} catch (IOException e) {
			System.err.println("Exception:  " + e);
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		return (null);
	}
	
	
	/*
	 *  UI 관련 메서드
	 *  윈도우 크기는 4로 가정 ( 패킷 순서번호(8) / 2 ) 
	 */
	static void printBuf(int base) {
	      System.out.println("┌───────────────────────────────────┐");
	      System.out.println("│ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │");
	      System.out.println("└───────────────────────────────────┘");
	      
	      if(base > 0) {
	          for(int i=0; i<base; i++) {
	        	  System.out.print("    ");
	          }
	      }
	      System.out.println("└───────────────┘\n");
	}
}