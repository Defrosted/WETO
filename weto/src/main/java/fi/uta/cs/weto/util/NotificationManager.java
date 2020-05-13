
        package fi.uta.cs.weto.util;

        import fi.uta.cs.weto.db.*;
        import org.apache.log4j.Logger;
        import org.apache.velocity.Template;
        import org.apache.velocity.VelocityContext;
        import org.apache.velocity.app.VelocityEngine;
        import org.apache.velocity.runtime.RuntimeConstants;
        import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

        import javax.mail.MessagingException;
        import javax.servlet.ServletContextEvent;
        import javax.servlet.ServletContextListener;
        import javax.servlet.annotation.WebListener;
        import java.io.*;
        import java.sql.Connection;
        import java.sql.SQLException;
        import java.util.ArrayList;
        import java.util.HashMap;
        import java.util.HashSet;
        import java.util.concurrent.Executors;
        import java.util.concurrent.ScheduledExecutorService;
        import java.util.concurrent.TimeUnit;


        @WebListener()
public class NotificationManager implements ServletContextListener {
    private static final Logger logger = Logger.getLogger(NotificationManager.class);

    private ScheduledExecutorService scheduler;

    private DbConnectionManager connectionManager;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        connectionManager = DbConnectionManager.getInstance();

        scheduler = Executors.newSingleThreadScheduledExecutor();

        int notificationEmailInterval = Integer.parseInt(
                WetoUtilities.getPackageResource("notification.emailInterval.minutes"));
        scheduler.scheduleAtFixedRate(new NotificationEmailTask(), 1, notificationEmailInterval, TimeUnit.MINUTES);

        scheduler.scheduleAtFixedRate(new DeadlineNotificationTask(), 1, notificationEmailInterval, TimeUnit.MINUTES);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        scheduler.shutdownNow();
    }


    private class DeadlineNotificationTask implements Runnable {

        @Override
        public void run() {

            Connection masterCon = connectionManager.getConnection("master");
            ArrayList<Permission> activeTasks = new ArrayList<>();

            try {
                activeTasks = Permission.selectActive(masterCon);
                logger.debug("loaded " + activeTasks.size() + " active courses");
            } catch (Exception e) {
                logger.error(e);
            }
            //Lets find all currently active tasks and their databases
            ArrayList<Integer> databases = new ArrayList<>();
            for (Permission masterTask : activeTasks) {
                try {

                    int databaseID = CourseImplementation.select1ByMasterTaskId(masterCon,masterTask.getId()).getDatabaseId();

                    //iterate a course database only once
                    if (!databases.contains(databaseID)) {
                        databases.add(databaseID);
                        String databaseName = DatabasePool.select1ById(masterCon, databaseID).getName();
                        Connection courseCon = connectionManager.getConnection(databaseName);
                        ArrayList<Permission> allActiveCoursePermissions = new ArrayList<>();
                        try {
                            allActiveCoursePermissions = Permission.selectActive(courseCon);
                        } catch (Exception e) {
                            logger.debug(e + "  with connection   " + courseCon);
                        }
                        
                         for (Permission permission : allActiveCoursePermissions) {
                             String asd = permission.getEndTimeStampString();
                             //Implement check deadline time here


                             //Implement teacher notifications here
                            //Check if permission is submission permission
                            if (permission.getType() == 1) {
                                HashSet<Integer> notSubmittedStudents = new HashSet<>();
                                boolean isAllUsersPermission = permission.getUserRefId() == null;
                                if (isAllUsersPermission) {
                                    try {
                                        Task temp = Task.select1ById(courseCon, permission.getTaskId());
                                        ArrayList<ClusterMember> memberList = ClusterMember.selectByClusterId(courseCon, temp.getRootTaskId());

                                        for (ClusterMember member : memberList) {
                                            notSubmittedStudents.add(member.getUserId());
                                        }
                                    } catch (Exception e) {
                                        logger.error(e);
                                    }
                                }
                                int assignmentTask = permission.getTaskId();

                                //one tasks submissions
                                ArrayList<Submission> taskSubmissions = Submission.selectByTaskId(courseCon, assignmentTask);
                                for (Submission sub : taskSubmissions) {
                                    int status = sub.getStatus();
                                    //submission status 2 == accepted
                                    if (status == 2 && isAllUsersPermission) {
                                        try {
                                            notSubmittedStudents.remove(sub.getUserId());
                                        } catch (Exception e) {
                                            logger.debug("There was no id to remove");
                                        }
                                    }
                                    if (status != 2 && !isAllUsersPermission) {
                                        notSubmittedStudents.add(sub.getUserId());
                                    }
                                }

                                    for (int student : notSubmittedStudents) {
                                        UserAccount user = UserAccount.select1ById(courseCon, student);
                                        UserAccount masterUser = UserAccount.select1ByLoginName(masterCon, user.getLoginName());
                                        int taskID = permission.getTaskId();
                                        Task temp = Task.select1ById(courseCon,taskID);
                                        int courseID = temp.getRootTaskId();

                                        //get courses ID in the master database
                                        int masterTaskID = CourseImplementation.select1ByDatabaseIdAndCourseTaskId(masterCon,databaseID,courseID).getMasterTaskId();


                                        try {
                                            Notification notification = new Notification(masterUser.getId(), masterTaskID, Notification.DEADLINE, "");
                                            notification.setMessage("<h2>Hei käyttäjä " + masterUser.getFirstName() +". Et ole tehnyt tehtävää nro. " + taskID + "</h2>");
                                            notification.createNotification(masterCon, courseCon);
                                        } catch (Exception e) {
                                            logger.error(e);
                                        }


                                    }
                                }
                            }


                        connectionManager.freeConnection(courseCon);
                    }
                } catch (Exception e) {
                    logger.error(e);
                }
            }
                connectionManager.freeConnection(masterCon);
        }
    }

    private class NotificationEmailTask implements Runnable {
        @Override
        public void run() {
            Connection masterConnection = connectionManager.getConnection("master");

            // Map notifications by user id into lists
            HashMap<Integer, ArrayList<Notification>> notificationMap = new HashMap<>();
            ArrayList<Notification> unsentNotifications;
            try {
                unsentNotifications = Notification.getNotificationsNotSentByEmail(masterConnection);
            } catch (Exception e) {
                logger.error("Failed to fetch unsent notifications", e);
                return;
            }
            for (Notification notification : unsentNotifications) {
                int userId = notification.getUserId();

                if(notificationMap.get(userId) == null) {
                    notificationMap.put(userId, new ArrayList<Notification>());
                }
                notificationMap.get(userId).add(notification);
            }

            // Setup Velocity templates for the email
            final String templateName = "NotificationEmailTemplate.vm";

            VelocityEngine velocityEngine = new VelocityEngine();
            velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
            velocityEngine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
            velocityEngine.setProperty(RuntimeConstants.FILE_RESOURCE_LOADER_CACHE, "true");
            velocityEngine.setProperty("runtime.log.logsystem.class", "org.apache.velocity.runtime.log.SimpleLog4JLogSystem");
            velocityEngine.setProperty("runtime.log.logsystem.log4j.category", "velocity");
            velocityEngine.setProperty("runtime.log.logsystem.log4j.logger", "velocity");

            Template template;
            try {
                velocityEngine.init();
                template = velocityEngine.getTemplate(templateName, "UTF-8");
            } catch (Exception e) {
                logger.error("Failed to initialize velocity engine", e);
                return;
            }

            // Form emails for each user
            for (int userId : notificationMap.keySet()) {
                ArrayList<Notification> notifications = notificationMap.get(userId);

                UserAccount userAccount;
                try {
                    userAccount = UserAccount.select1ById(masterConnection, userId);
                } catch (Exception e) {
                    logger.error("Failed to fetch user account", e);
                    break;
                }

                HashMap<Integer, String> courseNameMap = new HashMap<>();
                try {
                    for(Notification notification : notifications) {
                        int courseId = notification.getCourseId();
                        courseNameMap.put(courseId, Task.select1ById(masterConnection, courseId).getName());
                    }
                } catch (Exception e) {
                    logger.error("Failed to fetch course name", e);
                    break;
                }

                try (StringWriter stringWriter = new StringWriter()) {
                    String emailSubject = String.format("WETO: %s new notification(s)", notifications.size());
                    String emailMessage;

                    // Set up the context for velocity and evaluate the template
                    VelocityContext velocityContext = new VelocityContext();
                    velocityContext.put("emailTitle", emailSubject);
                    velocityContext.put("courseNames", courseNameMap);
                    velocityContext.put("notifications", notifications);
                    velocityContext.put("notificationTypeMap", Notification.getTypeDisplayMap());

                    template.merge(velocityContext, stringWriter);

                    stringWriter.flush();
                    emailMessage = stringWriter.toString();

                    Email.sendMail(userAccount.getEmail(), emailSubject, emailMessage);
                } catch (IOException e) {
                    logger.error("Failed to create a html from the email template", e);
                    break;
                } catch (MessagingException e) {
                    logger.error("Failed to send automated notification email", e);
                    break;
                }

                // Save the notifications as sent
                for(Notification notification : notifications) {
                    try {
                        notification.setSentByEmail(true);
                        notification.update(masterConnection);
                    } catch (Exception e) {
                        logger.error("Failed to save notification state during automated emailing", e);
                    }
                }
            }

            try {
                masterConnection.commit();
            } catch (Exception e) {
                try {
                    masterConnection.rollback();
                } catch (SQLException ignored) {
                }
            }

            connectionManager.freeConnection(masterConnection);
        }
    }
}

