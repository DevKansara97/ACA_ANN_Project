import java.io.File;
import java.util.Random;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;

// JDK 11+: java -Djava.awt.headless=true -cp logisim-generic-2.7.1.jar tests/BuiltinMultiplierTest.java
public class BuiltinMultiplierTest {
    private static final int MASK = (1 << 25) - 1;

    private static Component pin(Circuit circuit, String label) {
        return circuit.getNonWires().stream()
            .filter(c -> c.getFactory() instanceof Pin)
            .filter(c -> label.equals(c.getAttributeSet().getValue(StdAttr.LABEL)))
            .findFirst().orElseThrow(() -> new AssertionError("Missing pin: " + label));
    }

    private static int read(CircuitState state, Component pin, int width) {
        Value value = Pin.FACTORY.getValue(state.getInstanceState(pin));
        if (!value.isFullyDefined() || value.getWidth() != width) {
            throw new AssertionError("Invalid output: " + value);
        }
        return value.toIntValue();
    }

    private static void check(CircuitState state, Component[] pins, int a, int b) {
        for (int i = 0; i < 2; i++) {
            Pin.FACTORY.setValue(state.getInstanceState(pins[i]),
                Value.createKnown(BitWidth.create(25), (i == 0 ? a : b) & MASK));
            state.markComponentAsDirty(pins[i]);
        }
        state.getPropagator().propagate();
        if (state.getPropagator().isOscillating()) throw new AssertionError("Did not settle");
        int lo = read(state, pins[2], 25);
        int hi = read(state, pins[3], 25);
        int signedHi = (hi << 7) >> 7;
        long actual = ((long) signedHi << 25) | lo;
        long expected = (long) a * b;
        if (actual != expected) {
            throw new AssertionError("A=" + a + ", B=" + b + ": expected " + expected
                + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        try {
            File path = new File(args.length == 0 ? "project.circ" : args[0]);
            LogisimFile file = new Loader(null).openLogisimFile(path);
            if (!file.getMainCircuit().getName().equals("neuron")) {
                throw new AssertionError("Project must open on the complete neuron circuit");
            }
            for (Circuit circuit : file.getCircuits()) {
                if (circuit.getName().startsWith("baugh_")
                    || circuit.getName().startsWith("signed_shift_add")) {
                    throw new AssertionError("Obsolete multiplier restored: " + circuit.getName());
                }
            }
            Project project = new Project(file);
            int[] edges = {-(1 << 24), -(1 << 24) + 1, -(1 << 23), -(1 << 18),
                -2, -1, 0, 1, 2, 1 << 18, 1 << 23, (1 << 24) - 2, (1 << 24) - 1,
                (int) Math.round(-0.92243 * (1 << 18)),
                (int) Math.round(-7.9664 * (1 << 18)),
                (int) Math.round(14.7048 * (1 << 18))};
            for (String name : new String[]{"signed_builtin_25x25"}) {
                Circuit circuit = file.getCircuit(name);
                CircuitState state = new CircuitState(project, circuit);
                Component[] pins = {pin(circuit, "A"), pin(circuit, "B"), pin(circuit, "P_LO"), pin(circuit, "P_HI")};
                for (int i = 0; i < 2; i++) {
                    if (pins[i].getAttributeSet().getValue(StdAttr.WIDTH).getWidth() != 25) {
                        throw new AssertionError("Input must be 25 bits");
                    }
                }
                for (int a : edges) for (int b : edges) check(state, pins, a, b);
                Random random = new Random(25);
                for (int i = 0; i < 10000; i++) {
                    check(state, pins, random.nextInt(1 << 25) - (1 << 24),
                        random.nextInt(1 << 25) - (1 << 24));
                }
                if (circuit.getWidthIncompatibilityData() != null
                    && !circuit.getWidthIncompatibilityData().isEmpty()) {
                    throw new AssertionError("Bus width mismatch in " + name);
                }
                System.out.println(name + ": " + (edges.length * edges.length)
                    + " boundary pairs and 10,000 random pairs passed (full 50-bit product)");
            }
            for (Circuit circuit : file.getCircuits()) {
                CircuitState state = new CircuitState(project, circuit);
                state.getPropagator().propagate();
                if (circuit.getWidthIncompatibilityData() != null
                    && !circuit.getWidthIncompatibilityData().isEmpty()) {
                    throw new AssertionError("Bus width mismatch in " + circuit.getName());
                }
            }
            for (Circuit circuit : file.getCircuits()) {
                CircuitState state = new CircuitState(project, circuit);
                state.getPropagator().propagate();
                for (Component component : circuit.getNonWires()) {
                    if (!(component.getFactory() instanceof SubcircuitFactory)) continue;
                    for (com.cburch.logisim.comp.EndData end : component.getEnds()) {
                        if (!state.getValue(end.getLocation()).isFullyDefined()) {
                            throw new AssertionError("Floating/error subcircuit port in "
                                + circuit.getName() + " at " + end.getLocation());
                        }
                    }
                }
            }
            System.out.println("All project circuits have compatible bus widths");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
