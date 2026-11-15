# ANN Logisim project

Open **`project.circ`** in Logisim 2.7.1. It opens on **neuron**, showing the end-to-end path: three full products, their sum, bias addition, and a clearly marked dummy tanh activation. Reopen the file if Logisim has an older copy open; saving that older window can overwrite external updates. Use the Poke Tool (hand icon) to toggle input bits.

## Circuits and formats

- `signed_builtin_25x25`: reusable signed multiplier. Inputs A and B are 25-bit fxp(6,18); its exact 50-bit product is fxp(13,36), exposed as P_HI (25 bits) and P_LO (25 bits).
- `sigma`: inputs x1, w1, x2, w2, x3, w3; computes Y = x1*w1 + x2*w2 + x3*w3. Final Y is signed **fxp(14,36)**, exposed as Y_HI (26 bits) and Y_LO (25 bits).
- `neuron`: inputs A, A_W, B, B_W, C, C_w, plus wb_HI/wb_LO (one logical bias); computes z = A*A_W + B*B_W + C*C_w + wb and passes it to the dummy activation. Its final Y is a 25-bit fxp(6,18) output, currently fixed at 0.0.
- `f_tanh_plan`: **dummy tanh** with Z_HI (26-bit) and Z_LO (25-bit) inputs carrying signed fxp(14,36). Its 25-bit fxp(6,18) output Y is always 0.0, regardless of the inputs. The actual tanh approximation is intentionally not implemented.
- `main`: empty placeholder.

Select a destination circuit, single-click `signed_builtin_25x25` or sigma in the left circuit list, then click the canvas to place it as a component. Double-click a circuit name to inspect its internals.

The notation excludes the sign bit from the integer-bit count:

```text
Input:   fxp(6,18)  = 1 sign +  6 integer + 18 fractional = 25 bits
Product: fxp(13,36) = 1 sign + 13 integer + 36 fractional = 50 bits
Sum Y:   fxp(14,36) = 1 sign + 14 integer + 36 fractional = 51 bits
```

## Entering inputs

Each input ranges from −64 to `64 − 2^-18`, in steps of `2^-18` (approximately 0.000003815). Encode a decimal x as `round(x * 262144)` in 25-bit two's complement.

```text
+1.0 = 0000001000000000000000000 = 0040000 hex
−1.0 = 1111111000000000000000000 = 1FC0000 hex
```

The first bit is part of two's-complement encoding, not a separate sign-magnitude flag.

## Reading full-precision outputs

Logisim 2.7.1 limits individual buses to 32 bits, so each wide result is exposed in two chunks. They form one logical number: concatenate HI first, then LO. The LO chunk is unsigned; the HI chunk carries the sign.

For one multiplier:

```text
product_raw   = signed_25(P_HI) * 2^25 + unsigned_25(P_LO)
product_value = product_raw / 2^36
```

For sigma's full-precision sum:

```text
Y_raw   = signed_26(Y_HI) * 2^25 + unsigned_25(Y_LO)
Y_value = Y_raw / 68719476736
```

The binary point is **36 bits from the right** of sigma's concatenated Y. This means Y_HI contains the sign, 14 integer bits, and the first 11 fractional bits; Y_LO contains the remaining 25 fractional bits. Do not interpret either chunk alone as the full result.

For example, set all three input/weight pairs to −1.0 × −1.0. The full sum is +3.0:

```text
Y_HI = 0001800 hex (6144)
Y_LO = 0000000 hex (0)
Y    = (6144 * 2^25) / 2^36 = 3.0
```

## Arithmetic

Each multiplier converts its two inputs to unsigned magnitudes, multiplies them with one built-in 25-bit Multiplier, and retains both the normal output and c_out. An XOR of the input sign bits selects the positive product or its full 50-bit two's-complement negation. Negation uses two 25-bit subtractors with borrow carried from the low half into the high half. The most-negative input's magnitude is correctly retained as unsigned `2^24`.

There is **no product slicing, fractional shifting, rounding, saturation, or intermediate wrap to fxp(6,18)**. Products retain all 36 fractional bits.

Sigma adds P1 + P2, then adds P3. At each stage, a 25-bit Adder adds the low halves, and its carry-out feeds the carry-in of a 26-bit Adder for the high halves. Each product's high half is sign-extended from 25 to 26 bits before addition; that sign-extends the complete 50-bit product to 51 bits. Only carry beyond the final 51-bit result is discarded.

The largest product is (−64)*(−64) = +4096, and three such products give +12288. This fits fxp(14,36), whose range is −16384 to almost +16384. The chosen width holds every sum of three valid input-pair products without overflow. No post-addition truncation is necessary to obtain fxp(14,36).

Decimal inputs can still have initial quantization error. The arithmetic is exact for their encoded fixed-point values. The circuit is combinational and needs no clock.

## Bias and dummy activation

The neuron computes:

```text
sum = A*A_W + B*B_W + C*C_w
z   = sum + wb
y   = DUMMY_tanh(z) = 0.0
```

The bias is now entered **directly in fxp(14,36)**, matching the sum. Supply its one logical 51-bit value as wb_HI (26 bits, signed) and wb_LO (25 bits, unsigned). There is no WB_RAW signal, splitter, bit extender, or bias-format conversion in the neuron. Two ordinary built-in adders add the matching high/low halves, with low carry-out connected to high carry-in.

```text
wb_value = (signed_26(wb_HI) * 2^25 + unsigned_25(wb_LO)) / 2^36
```

For wb = +1.0, enter wb_HI = `0000800` hex and wb_LO = `0000000`. For wb = −1.0, enter wb_HI = `3FFF800` and wb_LO = `0000000`. Zero bias means both pins are zero. The other six inputs still use fxp(6,18); bias uses the sum's format so it can be added directly.

The internal Z_LO/Z_HI tunnel nets carry the normal addition result and connect to the dummy tanh inputs. With the original bias range of ±64, the full sum plus bias fits fxp(14,36). Supplying a much larger bias can overflow the 51-bit addition, which wraps.

**The final neuron output Y is currently a fixed zero, not the mathematical tanh of z.** The module accepts the expected input interface but intentionally ignores its values. The zero constant is a 25-bit fxp(6,18) value and lies within tanh's range. It provides a stable interface that the real activation implementation can replace later without rewiring the neuron.

To inspect arithmetic while the dummy is active, use the Poke Tool on the Z_HI/Z_LO wires or inspect sigma. Changing any input or wb will change z as appropriate but will not change the final dummy Y.

## Verification

With JDK 11+, run from this directory:

```sh
java -Djava.awt.headless=true -cp logisim-generic-2.7.1.jar tests/BuiltinMultiplierTest.java
java -Djava.awt.headless=true -cp logisim-generic-2.7.1.jar tests/SigmaSumTest.java
java -Djava.awt.headless=true -cp logisim-generic-2.7.1.jar tests/NeuronDummyTanhTest.java
```

The multiplier test checks the exported 50-bit product against Java integer multiplication for 256 boundary/example pairs and 10,000 deterministic random pairs. It also checks bus widths and defined subcircuit interfaces throughout the project.

Sigma passes 14 edge/example cases and 5,000 deterministic random six-input cases, comparing the exact 51-bit result against a Java long sum of three integer products. Tests cover both signs, zero, carry between halves, cancellation, input limits, the user's decimal examples, the maximum +12288 sum, and tiny products that would have disappeared under the previous truncation. Testing is not exhaustive over the input space.

The dummy tanh test exercises 15 standalone signed input patterns. The neuron test checks 11 bias/edge cases and 5,000 deterministic random cases across six product inputs and the two-chunk bias against the exact Java long result, including direct addition of positive and negative bias, carry, and full-range product inputs. A separate check verifies that a bias of raw 1 is added unchanged, without scaling. It also verifies that z reaches both activation inputs and that the final 25-bit Y remains the requested constant zero.
