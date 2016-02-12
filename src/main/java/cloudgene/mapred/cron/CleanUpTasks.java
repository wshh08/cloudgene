package cloudgene.mapred.cron;

import genepi.hadoop.HdfsUtil;
import genepi.io.FileUtil;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Public;

import cloudgene.mapred.WebApp;
import cloudgene.mapred.database.JobDao;
import cloudgene.mapred.database.util.Database;
import cloudgene.mapred.jobs.AbstractJob;
import cloudgene.mapred.util.MailUtil;
import cloudgene.mapred.util.Settings;
import cloudgene.mapred.util.Template;

public class CleanUpTasks {

	private static final Log log = LogFactory.getLog(CleanUpTasks.class);

	public static int executeRetire(Database database, Settings settings) {
		JobDao dao = new JobDao(database);

		List<AbstractJob> oldJobs = dao.findAllNotifiedJobs();

		int deleted = 0;
		for (AbstractJob job : oldJobs) {

			if (job.getDeletedOn() < System.currentTimeMillis()) {

				// delete local directory and hdfs directory
				String localOutput = FileUtil.path(
						settings.getLocalWorkspace(), job.getId());

				String hdfsOutput = HdfsUtil.makeAbsolute(HdfsUtil.path(
						settings.getHdfsWorkspace(), job.getId()));

				FileUtil.deleteDirectory(localOutput);
				HdfsUtil.delete(hdfsOutput);

				job.setState(AbstractJob.STATE_RETIRED);
				dao.update(job);

				log.info("Job " + job.getId() + " retired.");
				deleted++;

			}

		}

		log.info(deleted + " jobs retired.");
		return deleted;
	}

	public static int sendNotifications(WebApp application) {

		Database database = application.getDatabase();
		Settings settings = application.getSettings();

		int days = settings.getRetireAfter() - settings.getNotificationAfter();

		JobDao dao = new JobDao(database);

		List<AbstractJob> oldJobs = dao
				.findAllOlderThan(settings.getNotificationAfterInSec(),
						AbstractJob.STATE_SUCCESS);

		int send = 0;

		for (AbstractJob job : oldJobs) {

			try {

				String subject = "[" + settings.getName() + "] Job "
						+ job.getName() + " will be retired in " + days
						+ " days";

				String body = application.getTemplate(Template.RETIRE_JOB_MAIL,
						job.getUser().getFullName(), days, job.getName());

				if (!job.getUser().getUsername().equals("public")) {

					MailUtil.send(settings, job.getUser().getMail(), subject,
							body);

				}

				job.setState(AbstractJob.STATE_SUCESS_AND_NOTIFICATION_SEND);
				job.setDeletedOn(System.currentTimeMillis()
						+ ((settings.getRetireAfterInSec() - settings
								.getNotificationAfterInSec()) * 1000));

				log.info("Sent notification for job " + job.getId() + ".");
				send++;
				dao.update(job);

			} catch (Exception e) {

				log.error("Sent notification for job " + job.getId()
						+ " failed.", e);

			}

		}

		oldJobs = dao.findAllOlderThan(settings.getNotificationAfterInSec(),
				AbstractJob.STATE_FAILED);

		int otherJobs = 0;

		for (AbstractJob job : oldJobs) {

			log.info("Job failed, no notification sent for job " + job.getId()
					+ ".");
			job.setState(AbstractJob.STATE_FAILED_AND_NOTIFICATION_SEND);
			job.setDeletedOn(System.currentTimeMillis()
					+ ((settings.getRetireAfterInSec() - settings
							.getNotificationAfterInSec()) * 1000));
			dao.update(job);
			otherJobs++;

		}

		oldJobs = dao.findAllOlderThan(settings.getNotificationAfterInSec(),
				AbstractJob.STATE_CANCELED);

		for (AbstractJob job : oldJobs) {

			log.info("Job failed, no notification sent for job " + job.getId()
					+ ".");
			job.setState(AbstractJob.STATE_FAILED_AND_NOTIFICATION_SEND);
			job.setDeletedOn(System.currentTimeMillis()
					+ ((settings.getRetireAfterInSec() - settings
							.getNotificationAfterInSec()) * 1000));
			dao.update(job);
			otherJobs++;

		}

		log.info(send + " notifications sent. " + otherJobs
				+ " jobs marked without email ntofication.");

		return send;

	}

}
