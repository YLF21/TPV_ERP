package com.tpverp.backend.promotion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** For a rate whose eligible units have one price in one sorted pack group,
 * taking the rate removes one contiguous price block (ties may be reordered).
 * The only state needed between blocks is how many units are in the open pack. */
final class OrderedRateChoices {
    private static final long MAX_CELLS = 2_000_000;
    private OrderedRateChoices() {}

    static BitSet choose(List<List<OrderedBuyPayPricing.Row>> groups, long packSize,
                         List<PromotionBenefit> rates) {
        if (packSize > MAX_CELLS) return null;
        int size = (int) packSize;
        var seen = new HashSet<Integer>();
        var selected = new BitSet();
        long cells = 0;
        for (var group : groups) {
            var blocks = new LinkedHashMap<Key, Block>();
            for (var row : group) {
                var key = new Key(row.line().unitPrice().stripTrailingZeros(), row.quantityRate());
                var block = blocks.get(key);
                if (block == null) {
                    // A rate spanning two pack groups or two distinct prices isn't a
                    // single decision here; the unrestricted exact solver handles it.
                    if (key.rate >= 0 && !seen.add(key.rate)) return null;
                    block = new Block(key);
                    blocks.put(key, block);
                }
                block.add(row.quantity(), size);
            }
            cells += (long) blocks.size() * size;
            if (cells > MAX_CELLS) return null;
            selectGroup(new ArrayList<>(blocks.values()), size, rates, selected);
        }
        return selected;
    }

    private static void selectGroup(List<Block> blocks, int size, List<PromotionBenefit> rates, BitSet selected) {
        var best = new BigDecimal[size];
        best[0] = BigDecimal.ZERO;
        // A set bit indicates the rate was chosen for this block and ending remainder.
        var takeRate = new BitSet[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i);
            var next = new BigDecimal[size];
            var decisions = new BitSet(size);
            takeRate[i] = decisions;
            for (int pending = 0; pending < size; pending++) {
                if (best[pending] == null) continue;
                int end = pending + block.remainder;
                var value = best[pending].add(block.packSaving);
                if (end >= size) {
                    end -= size;
                    value = value.add(block.key.price);
                }
                if (next[end] == null || value.compareTo(next[end]) > 0) {
                    next[end] = value;
                    decisions.clear(end);
                }
                if (block.key.rate >= 0) {
                    value = best[pending].add(rates.get(block.key.rate).payableAmount());
                    if (next[pending] == null || value.compareTo(next[pending]) > 0) {
                        next[pending] = value;
                        decisions.set(pending);
                    }
                }
            }
            best = next;
        }
        int end = -1;
        for (int i = 0; i < size; i++) {
            if (best[i] != null && (end < 0 || best[i].compareTo(best[end]) > 0)) end = i;
        }
        for (int i = blocks.size() - 1; i >= 0; i--) {
            var block = blocks.get(i);
            if (takeRate[i].get(end)) selected.set(block.key.rate);
            else end = Math.floorMod(end - block.remainder, size);
        }
    }

    private record Key(BigDecimal price, int rate) {}

    private static final class Block {
        final Key key;
        BigDecimal packSaving = BigDecimal.ZERO;
        int remainder;

        Block(Key key) { this.key = key; }

        void add(long quantity, int size) {
            long complete = quantity / size;
            remainder += (int) (quantity % size);
            if (remainder >= size) {
                remainder -= size;
                complete++;
            }
            packSaving = packSaving.add(key.price.multiply(BigDecimal.valueOf(complete)));
        }
    }
}
