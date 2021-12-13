import java.io.Serializable;

public class AckPacket implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -2141103629201327344L;
	int cksum, len, ackno;
	
	public AckPacket(int len, int ackno) {
		this.len = len;
		this.ackno = ackno;
	}
}
