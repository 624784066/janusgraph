package com.thinkaurelius.titan.hadoop.scan;

import com.thinkaurelius.titan.diskstorage.configuration.*;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.scan.ScanJob;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.scan.ScanMetrics;
import com.thinkaurelius.titan.hadoop.config.ModifiableHadoopConfiguration;
import com.thinkaurelius.titan.hadoop.config.TitanHadoopConfiguration;
import com.thinkaurelius.titan.hadoop.formats.cassandra.CassandraBinaryInputFormat;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;

import java.io.IOException;
import java.util.Map;

import static com.thinkaurelius.titan.hadoop.compat.HadoopCompatLoader.DEFAULT_COMPAT;

/**
 * Utility class to construct and submit Hadoop Jobs that execute a {@link HadoopScanMapper}.
 */
public class HadoopScanRunner {

    /**
     * Run a ScanJob on Hadoop MapReduce.
     * <p>
     * The {@code scanJob} instance passed into this method doesn't actually
     * run the computation.  This method calls {@code scanJob.getClass().getName()}
     * and stores the return value in a MapReduce Job configuration.  One or more
     * instances of the class will be reflectively instantiated in the MapReduce
     * task process(es).
     * <p>
     * The {@code confRootField} parameter must be a string in the format
     * {@code package.package...class#fieldname}, where {@code fieldname} is the
     * name of a public static field on the class specified by the portion of the
     * string before the {@code #}.  The {@code #} itself is just a separator and
     * is discarded.
     * <p>
     * When a MapReduce task process prepares to execute the {@code ScanJob}, it will
     * read the public static field named by {@code confFieldRoot} and cast it to a
     * {@link ConfigNamespace}.  This namespace object becomes the root of a
     * {@link Configuration} instantiated, populated with the key-value pairs
     * from the {@code conf} parameter, and then passed into the {@code ScanJob}.
     * <p>
     * This method blocks until the ScanJob completes, then returns the metrics
     * generated by the job during its execution.  It does not timeout.
     *
     * @param scanJob a ScanJob to execute
     * @param conf configuration settings for the ScanJob
     * @param confRootField the root of the ScanJob's configuration
     * @return metrics generated by the ScanJob
     * @throws IOException if the job fails for any reason
     * @throws ClassNotFoundException if {@code scanJob.getClass()} or if Hadoop
     *         MapReduce's internal job-submission-related reflection fails
     * @throws InterruptedException if interrupted while waiting for the Hadoop
     *         MapReduce job to complete
     */
    public static ScanMetrics run(ScanJob scanJob, Configuration conf, String confRootField) throws IOException, InterruptedException, ClassNotFoundException {
        ModifiableHadoopConfiguration scanConf = ModifiableHadoopConfiguration.of(TitanHadoopConfiguration.SCAN_NS,
                new org.apache.hadoop.conf.Configuration());

        ConfigNamespace confRoot = HadoopScanMapper.getJobRoot(confRootField);

        ModifiableConfiguration hadoopJobConf = ModifiableHadoopConfiguration.subset(confRoot, TitanHadoopConfiguration.JOB_CONFIG_KEYS, scanConf);

        Map<String, Object> jobConfMap = conf.getSubset(confRoot);

        // TODO This is super ugly
        for (Map.Entry<String, Object> jobConfEntry : jobConfMap.entrySet()) {
            hadoopJobConf.set((ConfigOption) ConfigElement.parse(confRoot, jobConfEntry.getKey()).element, jobConfEntry.getValue());
        }

        Class.forName(scanJob.getClass().getName());

        scanConf.set(TitanHadoopConfiguration.JOB_CLASS, scanJob.getClass().getName());
        scanConf.set(TitanHadoopConfiguration.JOB_CONFIG_ROOT, confRootField);

        scanConf.getHadoopConfiguration().set("cassandra.input.partitioner.class","org.apache.cassandra.dht.Murmur3Partitioner");

        // TODO the following is probably not compatible across Hadoop 1/2
        Job job = Job.getInstance(scanConf.getHadoopConfiguration());

        job.setJarByClass(HadoopScanMapper.class);
        job.setJobName(HadoopScanMapper.class.getSimpleName() + "[" + scanJob + "]");
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(NullWritable.class);
        job.setMapOutputKeyClass(NullWritable.class);
        job.setMapOutputValueClass(NullWritable.class);
        job.setNumReduceTasks(0);
        job.setMapperClass(HadoopScanMapper.class);
        job.setOutputFormatClass(NullOutputFormat.class);
        job.setInputFormatClass(CassandraBinaryInputFormat.class);

        boolean success = job.waitForCompletion(true);

        if (!success) {
            String f;
            try {
                // Just in case one of Job's methods throws an exception
                f = String.format("MapReduce JobID %s terminated in state %s",
                        job.getJobID().toString(), job.getStatus().getState().name());
            } catch (Throwable t) {
                f = "Job failed (see MapReduce logs for more information)";
            }
            throw new IOException(f);
        } else {
            return DEFAULT_COMPAT.getMetrics(job.getCounters());
        }
    }
}
