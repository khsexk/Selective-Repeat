import java.io.Serializable;

public class AckPacket implements Serializable{
	int cksum, len, ackNo;
	
	public AckPacket(int len, int ackNo) {
		this.len = len;
		this.ackNo = ackNo;
	}
}
