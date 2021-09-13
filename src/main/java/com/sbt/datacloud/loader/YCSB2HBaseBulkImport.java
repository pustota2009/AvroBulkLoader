package com.sbt.datacloud.loader;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import org.apache.commons.lang.ArrayUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat2;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.compress.SnappyCodec;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;


public class YCSB2HBaseBulkImport extends Configured implements Tool {

    static String tableName;
    static Integer batch;
    static int zeropadding = 1;
    protected static String buildKeyName(long keynum) {
        keynum = Utils.hash(keynum);
        String value = Long.toString(keynum);
        int fill = zeropadding - value.length();
        String prekey = "user";
        for (int i = 0; i < fill; i++) {
            prekey += '0';
        }
        return prekey + value;
    }

    public static void main(String[] args) throws Exception {

        if (args.length != 7 && args.length != 3) {
            System.err.println("Usage: YCSB2HBaseBulkImport gen-keys <start-num> <stop-num>");
            System.err.println("Usage: YCSB2HBaseBulkImport <TableName> <path to hbase-site.xml> <input> <output> <map.memory.mb> <reduce.memory.mb> <am.memory.mb>");
            return;
        }

        if (args.length == 3 && args[0].equals("gen-keys")) {
            long start = Long.valueOf(args[1]);
            NumberGenerator keysequence = new CounterGenerator(start);
            long stop = Long.valueOf(args[2]);
            for (long i = start; i < stop; i++) {
                int keynum = keysequence.nextValue().intValue();
                String dbkey = buildKeyName(keynum);
                System.out.println(dbkey);
            }
        } else {
            int exitCode = ToolRunner.run(HBaseConfiguration.create(), new YCSB2HBaseBulkImport(), args);
            System.exit(exitCode);
        }
    }


    public int run(String[] args) throws Exception {

        tableName = args[0];

        Configuration conf = HBaseConfiguration.create();
        conf.addResource(new Path(args[1]));
        conf.set("mapreduce.map.memory.mb", args[4]);
        conf.set("mapreduce.reduce.memory.mb", args[5]);
        conf.set("yarn.app.mapreduce.am.resource.mb", args[6]);

        Job job = new Job(conf, getClass().getSimpleName());
        job.setJarByClass(getClass());

        Path input = new Path(args[2]);
        FileInputFormat.addInputPath(job, input);
        Path tmpPath = new Path(args[3]);
        FileOutputFormat.setOutputPath(job, tmpPath);

        job.setInputFormatClass(TextInputFormat.class);
        job.setMapperClass(HBaseMapper.class);
        job.setMapOutputKeyClass(ImmutableBytesWritable.class);
        job.setMapOutputValueClass(Put.class);
        job.setOutputFormatClass(HFileOutputFormat2.class);

        Connection connection = ConnectionFactory.createConnection(conf);
        Table table = connection.getTable(TableName.valueOf(tableName));
        Admin admin = connection.getAdmin();

        RegionLocator regionLocator = connection.getRegionLocator(TableName.valueOf(tableName));
        try {

            HFileOutputFormat2.configureIncrementalLoad(job, table, regionLocator);
            HFileOutputFormat2.setOutputPath(job, tmpPath);
            HFileOutputFormat2.setCompressOutput(job, true);
            HFileOutputFormat2.setOutputCompressorClass(job, SnappyCodec.class);

            if (!job.waitForCompletion(true)) {
                return 1;
            }

            //change permissions so that HBase user can read it
            FileSystem fs =  FileSystem.get(conf);
            FsPermission changedPermission=new FsPermission(FsAction.ALL,FsAction.ALL,FsAction.ALL);
            fs.setPermission(tmpPath, changedPermission);
            List<String> files = getAllFilePath(tmpPath, fs);
            for (String file : files) {
                fs.setPermission(new Path(file), changedPermission);
                System.out.println("Changing permission for file " + file);
            }


            //bulk load hbase files
            LoadIncrementalHFiles loader = new LoadIncrementalHFiles(conf);
            loader.doBulkLoad(tmpPath, (HTable) table);

            //delete the hfiles
            FileSystem.get(conf).delete(tmpPath, true);

            return 0;

        } finally {
            table.close();
            admin.close();
        }
    }

    static class HBaseMapper extends Mapper<LongWritable, Text, ImmutableBytesWritable, Put> {
        static final byte[] C_FAMILY_CURR = Bytes.toBytes("cf");

        NumberGenerator fieldlengthgenerator = new UniformLongGenerator(50, 50);

        @Override
        public void map(LongWritable offset, Text value, Context context)
                throws IOException, InterruptedException {

            for (int i = 0; i < 100; i++) {
                byte[] rowKey = makeHbaseRowKey(value.toString() + "_" + i);
                Put p = new Put(rowKey);

                p.addColumn(C_FAMILY_CURR, "field0".getBytes(), new RandomByteIterator(fieldlengthgenerator.nextValue().longValue()).toArray());
                p.addColumn(C_FAMILY_CURR, "field1".getBytes(), new RandomByteIterator(fieldlengthgenerator.nextValue().longValue()).toArray());
                p.addColumn(C_FAMILY_CURR, "field2".getBytes(), new RandomByteIterator(fieldlengthgenerator.nextValue().longValue()).toArray());
                p.addColumn(C_FAMILY_CURR, "field3".getBytes(), new RandomByteIterator(fieldlengthgenerator.nextValue().longValue()).toArray());
                p.addColumn(C_FAMILY_CURR, "field4".getBytes(), new RandomByteIterator(fieldlengthgenerator.nextValue().longValue()).toArray());
                p.addColumn(C_FAMILY_CURR, "field5".getBytes(), new RandomByteIterator(fieldlengthgenerator.nextValue().longValue()).toArray());
                p.addColumn(C_FAMILY_CURR, "field6".getBytes(), new RandomByteIterator(fieldlengthgenerator.nextValue().longValue()).toArray());
                p.addColumn(C_FAMILY_CURR, "field7".getBytes(), new RandomByteIterator(fieldlengthgenerator.nextValue().longValue()).toArray());
                p.addColumn(C_FAMILY_CURR, "field8".getBytes(), new RandomByteIterator(fieldlengthgenerator.nextValue().longValue()).toArray());
                p.addColumn(C_FAMILY_CURR, "field9".getBytes(), new RandomByteIterator(fieldlengthgenerator.nextValue().longValue()).toArray());
                context.write(new ImmutableBytesWritable(rowKey), p);
            }
        }
    }



    public static List<String> getAllFilePath(Path filePath, FileSystem fs) throws FileNotFoundException, IOException {
        List<String> fileList = new ArrayList<String>();
        FileStatus[] fileStatus = fs.listStatus(filePath);
        for (FileStatus fileStat : fileStatus) {
            if (fileStat.isDirectory()) {
                fileList.add(fileStat.getPath().toString());
                fileList.addAll(getAllFilePath(fileStat.getPath(), fs));
            } else {
                fileList.add(fileStat.getPath().toString());
            }
        }
        return fileList;
    }

    public static byte[] makeHbaseRowKey(String key) {
        byte[] nonSaltedRowKey = Bytes.toBytes(key);
        CRC32 crc32 = new CRC32();
        crc32.update(nonSaltedRowKey);
        long crc32Value = crc32.getValue();
        byte[] salt = Arrays.copyOfRange(Bytes.toBytes(crc32Value), 5, 7);
        return ArrayUtils.addAll(salt, nonSaltedRowKey);
    }
}
