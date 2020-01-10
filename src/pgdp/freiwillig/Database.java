package pgdp.freiwillig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class Database {
    private static File TBL_CUSTOMER = null, TBL_LINE_ITEM = null, TBL_ORDERS = null;
    private Map<String, Set<Long>> customers = new ConcurrentHashMap<>();
    private Map<Long, Set<Long>> orders = new ConcurrentHashMap<>();
    private Map<Long, Set<Long>> lineItems = new ConcurrentHashMap<>();

    public static void setBaseDataDirectory(Path baseDirectory) {
        TBL_CUSTOMER = new File(baseDirectory.toString() +
                File.separator + "customer.tbl");
        TBL_LINE_ITEM = new File(baseDirectory.toString() +
                File.separator + "lineitem.tbl");
        TBL_ORDERS = new File(baseDirectory.toString() +
                File.separator + "orders.tbl");
    }

    public Database() {
        processFile(TBL_CUSTOMER, this::processCustomerLine);
        processFile(TBL_ORDERS, this::processOrderLine);
        processFile(TBL_LINE_ITEM, this::processLineItemLine);
    }

    private void processFile(File file, Consumer<String> lineProcessor) {
        Queue<String> queue = new ConcurrentLinkedQueue<>();
        AtomicBoolean active = new AtomicBoolean(true);
        Future<?> task = processIndefinitely(active, queue, lineProcessor);
        try (BufferedReader ordersReader = Files.newBufferedReader(file.toPath())) {
            String line;
            while ((line = ordersReader.readLine()) != null) {
                queue.add(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            active.set(false);
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    private void processCustomerLine(String line) {
        int segmentSepEnd = line.lastIndexOf('|', line.length() - 2);
        int segmentSepStart = line.lastIndexOf('|', segmentSepEnd - 1);
        Long custKey = Long.parseUnsignedLong(line, 0, line.indexOf("|"), 10);
        String key = line.substring(segmentSepStart + 1, segmentSepEnd);
        customers.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(custKey);
    }

    private void processOrderLine(String line) {
        int custKeyFirst = line.indexOf("|") + 1;
        int custKeyLast = line.indexOf("|", custKeyFirst) - 1;
        Long custKey = Long.parseUnsignedLong(line, custKeyFirst, custKeyLast + 1, 10);
        Long orderKey = Long.parseUnsignedLong(line, 0, custKeyFirst - 1, 10);
        orders.computeIfAbsent(custKey, k -> ConcurrentHashMap.newKeySet())
                .add(orderKey);
    }

    private Future<?> processIndefinitely(AtomicBoolean cancellationSignal, Queue<String> feed, Consumer<String> processor) {
        return ForkJoinPool.commonPool().submit(() -> {
            while (true) {
                String next = feed.poll();
                if (next != null) {
                    processor.accept(next);
                } else if (!cancellationSignal.get()) {
                    break;
                }
            }
        });
    }

    private void processLineItemLine(String line) {
        int orderKeyLast = line.indexOf("|") - 1;
        Long orderKey = Long.parseUnsignedLong(line, 0, orderKeyLast + 1, 10);
        int sepFront = ordinalIndexOf(line, "|", 4, orderKeyLast + 1);
        int sepBack = line.indexOf("|", sepFront + 1);
        Long quantity = 100 * Long.parseUnsignedLong(line, sepFront + 1, sepBack, 10);
        lineItems.computeIfAbsent(orderKey, k -> ConcurrentHashMap.newKeySet())
                .add(quantity);
    }

    public long getAverageQuantityPerMarketSegment(String marketsegment) {
        final AtomicLong lineItemsCount = new AtomicLong();
        final AtomicLong totalQuantity = new AtomicLong();
        Set<Long> sgmtCustomers = customers.get(marketsegment);
        List<Future<?>> futures = new LinkedList<>();
        for (Long custKey : sgmtCustomers) {
            futures.add(ForkJoinPool.commonPool().submit(() -> {
                Set<Long> orderKeys = orders.get(custKey);
                if (orderKeys != null) {
                    for (Long orderKey : orderKeys) {
                        Set<Long> quantities = lineItems.get(orderKey);
                        if (quantities != null) {
                            lineItemsCount.addAndGet(quantities.size());
                            for (Long quantity : quantities) {
                                totalQuantity.addAndGet(quantity);
                            }
                        }
                    }
                }
            }));
        }
        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        return totalQuantity.get() / lineItemsCount.get();
    }

    public static void main(String[] args) {
        Database.setBaseDataDirectory(Paths.get("data"));
        long globalBefore = System.nanoTime();
        Database db = new Database();
        int[] durs = new int[5];
        long[] quants = new long[5];
        String[] segments = new String[]{"FURNITURE", "HOUSEHOLD", "AUTOMOBILE", "BUILDING", "MACHINERY"};
        for (int i = 0; i < segments.length; i++) {
            long before = System.nanoTime();
            long qt = db.getAverageQuantityPerMarketSegment(segments[i]);
            long after = System.nanoTime();
            durs[i] = (int) ((after - before) / Math.pow(10, 6));
            quants[i] = qt;
        }
        long globalAfter = System.nanoTime();
        long totalDur = 0;
        for (int i = 0; i < segments.length; i++) {
            totalDur += durs[i];
            System.out.println(segments[i] + ": average " + quants[i] + " took " + durs[i] + "ms");
        }
        System.out.println("total duration: " + (globalAfter - globalBefore) / Math.pow(10, 6));
        System.out.println("total average duration: " + (totalDur / durs.length) + "ms");
    }

    public static int ordinalIndexOf(String str, String substr, int n, int offset) {
        int pos = str.indexOf(substr, offset);
        while (--n > 0 && pos != -1)
            pos = str.indexOf(substr, pos + 1);
        return pos;
    }

    public static int parseInt(byte[] from, int begin, int length) {
        int value = 0;
        for (int i = begin + length - 1, pos = 1; i >= begin; i--, pos *= 10) {
            value += (from[i] ^ 0x30) * pos;
        }
        return value;
    }

}
