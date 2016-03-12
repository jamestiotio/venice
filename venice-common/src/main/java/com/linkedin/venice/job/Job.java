package com.linkedin.venice.job;

import java.util.List;


/**
 * Job is a approach to let cluster process off-line push, stream writing or data migration. It's composed by a
 * collection of task which will be executed by instances in cluster.
 */
public abstract class Job {

  private final long jobId;

  private final String kafkaTopic;

  private final int numberOfPartition;

  private final int replicaFactor;

  private ExecutionStatus status;

  public Job(long jobId, String kafkaTopic, int numberOfPartition, int replicaFactor) {
    this.jobId = jobId;
    this.numberOfPartition = numberOfPartition;
    this.replicaFactor = replicaFactor;
    this.kafkaTopic = kafkaTopic;
    this.status = ExecutionStatus.NEW;
  }

  public long getJobId() {
    return jobId;
  }

  public int getNumberOfPartition() {
    return numberOfPartition;
  }

  public int getReplicaFactor() {
    return replicaFactor;
  }

  public ExecutionStatus getStatus() {
    return status;
  }

  public void setStatus(ExecutionStatus status) {
    this.status = status;
  }

  public String getKafkaTopic() {
    return kafkaTopic;
  }

  /**
   * Check all of current tasks status to judge whether job is completed or error or still running.
   * <p>
   * Please not this method is only the query method which will not change the status of this job.
   *
   * @return Calcuated job status.
   */
  public abstract ExecutionStatus checkJobStatus();

  public abstract void updateTaskStatus(Task task);

  public abstract ExecutionStatus getTaskStatus(int partitionId, String taskId);

  public abstract Task getTask(int partitionId, String taskId);

  public abstract  void deleteTask(Task task);

  public abstract void setTask(Task task);

  public abstract List<Task> tasksInPartition(int partitionId);
}
