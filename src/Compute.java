import rubyko.java.rmi.Remote;
import rubyko.java.rmi.RemoteException;

public interface Compute extends Remote {

	double add(double p1, double p2) throws RemoteException;
	
}