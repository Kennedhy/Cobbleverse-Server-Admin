import br.com.cobbleverse.generations.rules.FormNames;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

public class FormNamesBench {
    private static final int OPERATIONS = 2_000_000;
    private static final String[] FORMS = {
        "Normal", "Alolan", "Galarian", "Hisuian", "Paldean", "Paldea", "Male", "Female",
        "Mega-X", "Mega X", "Ultra", "Original", "normal", "Alola", "Primal", "Dusk-Mane"
    };
    private static volatile long checksum;

    public static void main(String[] args) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long[] allocations = new long[3];
        long[] times = new long[3];
        run(OPERATIONS);
        for (int trial = 0; trial < times.length; trial++) {
            long bytes = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            run(OPERATIONS);
            times[trial] = System.nanoTime() - start;
            allocations[trial] = bean.getThreadAllocatedBytes(thread) - bytes;
        }
        Arrays.sort(times);
        Arrays.sort(allocations);
        System.out.printf("median_ns_per_call=%.2f median_bytes_per_call=%.2f checksum=%d%n",
            times[1] / (double) OPERATIONS, allocations[1] / (double) OPERATIONS, checksum);
    }

    private static void run(int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += FormNames.canonical(FORMS[i & (FORMS.length - 1)]).hashCode();
        }
        checksum = sum;
    }
}
