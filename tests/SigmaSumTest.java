import java.io.File;
import java.util.Arrays;
import java.util.Random;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;

// java -Djava.awt.headless=true -cp logisim-generic-2.7.1.jar tests/SigmaSumTest.java
public class SigmaSumTest {
    private static final int UNIT = 1 << 18;
    private static final int PRODUCT_MASK = (1 << 25) - 1;

    private static Component pin(Circuit circuit, String label) {
        return circuit.getNonWires().stream()
            .filter(c -> c.getFactory() instanceof Pin)
            .filter(c -> label.equals(c.getAttributeSet().getValue(StdAttr.LABEL)))
            .findFirst().orElseThrow(() -> new AssertionError("Missing pin " + label));
    }

    private static void check(CircuitState state, Component[] inputs, Component loOutput, Component hiOutput, int[] raw) {
        long expected = 0;
        for (int i = 0; i < 6; i++) {
            Pin.FACTORY.setValue(state.getInstanceState(inputs[i]),
                Value.createKnown(BitWidth.create(25), raw[i] & PRODUCT_MASK));
            state.markComponentAsDirty(inputs[i]);
            if (i % 2 == 1) {
                expected += (long) raw[i - 1] * raw[i];
            }
        }
        state.getPropagator().propagate();
        if (state.getPropagator().isOscillating()) throw new AssertionError("Circuit did not settle");
        Value lo = Pin.FACTORY.getValue(state.getInstanceState(loOutput));
        Value hi = Pin.FACTORY.getValue(state.getInstanceState(hiOutput));
        if (!lo.isFullyDefined() || !hi.isFullyDefined() || lo.getWidth() != 25 || hi.getWidth() != 26) {
            throw new AssertionError("Invalid 51-bit Y: " + hi + " / " + lo);
        }
        int signedHi = (hi.toIntValue() << 6) >> 6;
        long actual = ((long) signedHi << 25) | lo.toIntValue();
        if (actual != expected) {
            throw new AssertionError(state.getCircuit().getName() + " inputs=" + Arrays.toString(raw)
                + ": expected signed raw sum " + expected + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        try {
            LogisimFile file = new Loader(null).openLogisimFile(new File("project.circ"));
            Project project = new Project(file);
            int max = (1 << 24) - 1, min = -(1 << 24);
            int[][] examples = {
                {0, 0, 0, 0, 0, 0},
                {UNIT, UNIT, UNIT, UNIT, UNIT, UNIT},
                {-UNIT, UNIT, -UNIT, UNIT, -UNIT, UNIT},
                {-UNIT, -UNIT, -UNIT, UNIT, UNIT, UNIT},
                {40 * UNIT, UNIT, 40 * UNIT, UNIT, 40 * UNIT, UNIT},
                {-40 * UNIT, UNIT, -40 * UNIT, UNIT, -40 * UNIT, UNIT},
                {50 * UNIT, UNIT, 50 * UNIT, UNIT, -50 * UNIT, UNIT},
                {min, UNIT, min, UNIT, min, UNIT},
                {max, UNIT, max, UNIT, max, UNIT},
                {min, min, min, min, min, min},
                {1, 1, 1, 1, 1, 1},
                {-1, 1, -1, 1, -1, 1},
                {-1, 1, 1, 1, 0, max},
                {(int) Math.round(-0.92243 * UNIT), UNIT,
                    (int) Math.round(-7.9664 * UNIT), UNIT,
                    (int) Math.round(14.7048 * UNIT), UNIT}
            };
            for (String name : new String[]{"sigma"}) {
                Circuit circuit = file.getCircuit(name);
                CircuitState state = new CircuitState(project, circuit);
                String[] names = name.equals("sigma")
                    ? new String[]{"x1", "w1", "x2", "w2", "x3", "w3"}
                    : new String[]{"A", "A_W", "B", "B_W", "C", "C_w"};
                Component[] inputs = new Component[6];
                for (int i = 0; i < 6; i++) inputs[i] = pin(circuit, names[i]);
                Component loOutput = pin(circuit, "Y_LO"), hiOutput = pin(circuit, "Y_HI");
                for (int[] example : examples) check(state, inputs, loOutput, hiOutput, example);
                // Three (-64 * -64) terms must be +12288, preserving the largest full products.
                check(state, inputs, loOutput, hiOutput, examples[9]);
                long maximum = ((long) Pin.FACTORY.getValue(state.getInstanceState(hiOutput)).toIntValue() << 25)
                    | Pin.FACTORY.getValue(state.getInstanceState(loOutput)).toIntValue();
                if (maximum != (12288L << 36)) throw new AssertionError("Full-range sum lost bits");
                // Three raw 1*1 products must sum to 3; truncating each product would incorrectly give zero.
                check(state, inputs, loOutput, hiOutput, examples[10]);
                if (Pin.FACTORY.getValue(state.getInstanceState(loOutput)).toIntValue() != 3) {
                    throw new AssertionError("Fractional product bits were truncated before addition");
                }
                Random random = new Random(318);
                for (int n = 0; n < 5000; n++) {
                    int[] raw = new int[6];
                    for (int i = 0; i < 6; i++) raw[i] = random.nextInt(1 << 25) - (1 << 24);
                    check(state, inputs, loOutput, hiOutput, raw);
                }
                if (circuit.getWidthIncompatibilityData() != null
                    && !circuit.getWidthIncompatibilityData().isEmpty()) {
                    throw new AssertionError("Width conflict in " + name);
                }
                System.out.println(name + ": 14 edge/example cases and 5,000 random six-input cases passed");
            }
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
