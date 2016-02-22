
import rubyko.java.rmi.registry.LocateRegistry;
import rubyko.java.rmi.registry.Registry;
import java.math.BigDecimal;

public class ComputePi {
    public static void main(String args[]) {
        try {
            String name = "Compute";
            Registry registry = LocateRegistry.getRegistry("127.0.1.1", 2020);
            Compute comp = (Compute) registry.lookup(name);
            Pi task = new Pi(5);
            BigDecimal pi = comp.executeTask(task);
            System.out.println(pi);
        } catch (Exception e) {
            System.err.println("ComputePi exception:");
            e.printStackTrace();
        }
    }    
}