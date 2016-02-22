import java.io.IOException;

import rubyko.java.rmi.RemoteException;
import rubyko.java.rmi.registry.LocateRegistry;
import rubyko.java.rmi.registry.Registry;
import rubyko.java.rmi.server.UnicastRemoteObject;

class ComputeEngine implements Compute {

	public ComputeEngine() {
		super();
	}

	@Override
	public double add(double p1, double p2) throws RemoteException {
		return p1 + p2;
	}

	public static void main(String[] args) throws IOException {
		try {
			String name = "Compute";
			Compute engine = new ComputeEngine();
			Compute stub = (Compute) UnicastRemoteObject.exportObject(engine, 4444);
			Registry registry = LocateRegistry.createRegistry(2020);
			registry.rebind(name, stub);
			System.out.println("ComputeEngine bound");
		} catch (Exception e) {
			System.err.println("ComputeEngine exception:");
			e.printStackTrace();
		}
	}
}