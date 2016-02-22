
import rubyko.java.rmi.registry.LocateRegistry;
import rubyko.java.rmi.registry.Registry;

public class ComputePi {
    public static void main(String args[]) {
        try {
            String name = "Compute";
            Registry registry = LocateRegistry.getRegistry("127.0.1.1", 2020);
            Compute comp = (Compute) registry.lookup(name);
            double pi = comp.add(2, 2);
            System.out.println(pi);
        } catch (Exception e) {
            System.err.println("ComputePi exception:");
            e.printStackTrace();
        }
    }    
}