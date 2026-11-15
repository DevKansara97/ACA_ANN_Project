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

// java -Djava.awt.headless=true -cp logisim-generic-2.7.1.jar tests/NeuronDummyTanhTest.java
public class NeuronDummyTanhTest {
    private static final int MASK = (1 << 25) - 1;
    private static final int UNIT = 1 << 18;

    private static Component labeled(Circuit circuit, String type, String label) {
        return circuit.getNonWires().stream()
            .filter(c -> c.getFactory().getName().equals(type))
            .filter(c -> label.equals(c.getAttributeSet().getValue(StdAttr.LABEL)))
            .findFirst().orElseThrow(() -> new AssertionError("Missing " + type + ": " + label));
    }

    private static int read(CircuitState state, Component component, int width) {
        Value value = component.getFactory() instanceof Pin
            ? Pin.FACTORY.getValue(state.getInstanceState(component))
            : state.getValue(component.getLocation());
        if (!value.isFullyDefined() || value.getWidth() != width) {
            throw new AssertionError("Invalid signal: " + value);
        }
        return value.toIntValue();
    }

    private static void set(CircuitState state, Component pin, int width, int value) {
        Pin.FACTORY.setValue(state.getInstanceState(pin), Value.createKnown(BitWidth.create(width), value));
        state.markComponentAsDirty(pin);
    }

    private static void checkNeuron(CircuitState state, Component[] inputs, Component output,
                                    Component zLo, Component zHi, CircuitState activation, int[] raw) {
        long expected = (long) raw[6] << 18;
        for (int i = 0; i < 6; i++) {
            set(state, inputs[i], 25, raw[i] & MASK);
            if (i % 2 == 1) expected += (long) raw[i - 1] * raw[i];
        }
        long bias = (long) raw[6] << 18;
        set(state, inputs[6], 25, (int) bias & MASK);
        set(state, inputs[7], 26, (int) (bias >> 25) & ((1 << 26) - 1));
        state.getPropagator().propagate();
        if (state.getPropagator().isOscillating()) throw new AssertionError("Did not settle");
        int lo = read(state, zLo, 25), hi = read(state, zHi, 26);
        long actual = ((long) ((hi << 6) >> 6) << 25) | lo;
        if (actual != expected) {
            throw new AssertionError("Bias/preactivation mismatch: expected " + expected + ", got " + actual);
        }
        Circuit a = activation.getCircuit();
        if (read(activation, labeled(a, "Pin", "Z_LO"), 25) != lo
            || read(activation, labeled(a, "Pin", "Z_HI"), 26) != hi) {
            throw new AssertionError("Preactivation is not connected to tanh inputs");
        }
        if (read(state, output, 25) != 0) throw new AssertionError("Dummy neuron must output 0.0");
    }

    public static void main(String[] args) {
        try {
            LogisimFile file = new Loader(null).openLogisimFile(new File("project.circ"));
            Project project = new Project(file);
            Circuit tanh = file.getCircuit("f_tanh_plan");
            CircuitState tanhState = new CircuitState(project, tanh);
            Component hiInput = labeled(tanh, "Pin", "Z_HI"), loInput = labeled(tanh, "Pin", "Z_LO");
            Component dummyOutput = labeled(tanh, "Pin", "Y");
            int standalone = 0;
            for (int hi : new int[]{0, 1, (1 << 25) - 1, 1 << 25, (1 << 26) - 1}) {
                for (int lo : new int[]{0, 1, MASK}) {
                    set(tanhState, hiInput, 26, hi);set(tanhState, loInput, 25, lo);
                    tanhState.getPropagator().propagate();
                    if (read(tanhState, dummyOutput, 25) != 0) throw new AssertionError("Dummy tanh changed");
                    standalone++;
                }
            }
            System.out.println("Dummy tanh: " + standalone + " signed input patterns returned fixed 0.0");
            Circuit neuron = file.getCircuit("neuron");
            CircuitState state = new CircuitState(project, neuron);
            String[] names = {"A", "A_W", "B", "B_W", "C", "C_w", "wb_LO", "wb_HI"};
            Component[] inputs = new Component[8];
            for (int i = 0; i < 8; i++) inputs[i] = labeled(neuron, "Pin", names[i]);
            Component output = labeled(neuron, "Pin", "Y");
            Component zLo = labeled(neuron, "Tunnel", "Z_LO"), zHi = labeled(neuron, "Tunnel", "Z_HI");
            Component activationComponent = neuron.getNonWires().stream()
                .filter(c -> c.getFactory().getName().equals("f_tanh_plan")).findFirst().orElseThrow();
            state.getPropagator().propagate();
            CircuitState activation = ((SubcircuitFactory) activationComponent.getFactory())
                .getSubstate(state, activationComponent);
            int min = -(1 << 24), max = (1 << 24) - 1;
            int[][] examples = {
                {0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, UNIT}, {0, 0, 0, 0, 0, 0, -UNIT},
                {0, 0, 0, 0, 0, 0, 1}, {0, 0, 0, 0, 0, 0, -1},
                {0, 0, 0, 0, 0, 0, min}, {0, 0, 0, 0, 0, 0, max},
                {min, min, min, min, min, min, max},
                {min, max, min, max, min, max, min},
                {UNIT, UNIT, UNIT, UNIT, UNIT, UNIT, -UNIT},
                {-UNIT, UNIT, UNIT, UNIT, 0, max, -1}
            };
            for (int[] raw : examples) checkNeuron(state, inputs, output, zLo, zHi, activation, raw);
            Random random = new Random(118);
            for (int n = 0; n < 5000; n++) {
                int[] raw = new int[7];
                for (int i = 0; i < 7; i++) raw[i] = random.nextInt(1 << 25) - (1 << 24);
                checkNeuron(state, inputs, output, zLo, zHi, activation, raw);
            }
            // The bias is now supplied directly with 36 fractional bits; even its lowest bit must survive.
            checkNeuron(state, inputs, output, zLo, zHi, activation, examples[0]);
            set(state, inputs[6], 25, 1);
            state.getPropagator().propagate();
            if (read(state, zLo, 25) != 1 || read(state, zHi, 26) != 0) {
                throw new AssertionError("Direct fxp(14,36) bias was incorrectly rescaled");
            }
            if (neuron.getWidthIncompatibilityData() != null && !neuron.getWidthIncompatibilityData().isEmpty()) {
                throw new AssertionError("Neuron width conflict");
            }
            System.out.println("Neuron: 11 bias/edge cases and 5,000 random cases passed; z is correct, Y is dummy 0.0");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
