package fi.uta.cs.weto.actions.notifications;

import fi.uta.cs.sqldatamodel.NoSuchItemException;
import fi.uta.cs.weto.db.*;
import fi.uta.cs.weto.model.Tab;
import fi.uta.cs.weto.model.WetoActionException;
import fi.uta.cs.weto.model.WetoCourseAction;
import fi.uta.cs.weto.model.WetoMasterAction;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NotificationActions {
    public static class ViewNotificationSettings extends WetoCourseAction {
        private boolean saveFailed;
        private List<NotificationSetting> settings;

        public boolean getSaveFailed() {
            return saveFailed;
        }

        public void setSaveFailed(boolean saveFailed) {
            this.saveFailed = saveFailed;
        }

        public List<NotificationSetting> getSettings() {
            return settings;
        }

        public void setSettings(List<NotificationSetting> settings) {
            this.settings = settings;
        }

        public ViewNotificationSettings() {
            super(Tab.MAIN.getBit(), 0, 0, 0);
            settings = null;
            saveFailed = false;
        }

        @Override
        public String action() throws Exception {
            Connection courseConnection = getCourseConnection();
            int courseId = getCourseTaskId();
            int userId = getCourseUserId();

            try {
                settings = NotificationSetting.createSettings(courseConnection, userId, courseId);
            } catch (Exception e) {
                throw new WetoActionException("Failed to retrieve notification settings");
            }

            return SUCCESS;
        }
    }

    public static class SaveNotificationSettings extends WetoCourseAction {
        private Map<String, String> settingsMap;

        public Map<String, String> getSettingsMap() {
            return settingsMap;
        }

        public void setSettingsMap(Map<String, String> settingsMap) {
            this.settingsMap = settingsMap;
        }

        public SaveNotificationSettings() {
            super(Tab.MAIN.getBit(), 0, 0, 0);
        }

        @Override
        public String action() throws Exception {
            Connection courseConnection = getCourseConnection();
            int userId = getCourseUserId();
            int courseId = getCourseTaskId();

            try {
                List<NotificationSetting> currentSettings = NotificationSetting.selectByUserAndCourse(courseConnection, userId, courseId);
                for(NotificationSetting setting : currentSettings) {

                    try {
                        String notifications = settingsMap.get(setting.getType() + "_notifications");
                        setting.setNotifications(Boolean.parseBoolean(notifications));
                    } catch (NullPointerException e) {
                        setting.setNotifications(false);
                    }

                    try {
                        String emailNotifications = settingsMap.get(setting.getType() + "_emailNotifications");
                        setting.setEmailNotifications(Boolean.parseBoolean(emailNotifications));
                    } catch (NullPointerException e) {
                        setting.setEmailNotifications(false);
                    }

                    // Uncheck email notifications if notifications are off
                    if(!setting.isNotifications()) {
                        setting.setEmailNotifications(false);
                    }

                    setting.update(courseConnection);
                }
            } catch (Exception e) {
                throw new WetoActionException("Failed to save settings");
            }

            return SUCCESS;
        }
    }

    public static class ViewNotificationCenter extends WetoMasterAction {
        private ArrayList<Notification> notifications;
        private HashMap<Integer, String> notificationTypes;
        private HashMap<Integer, String> courseIdsNames;
        private HashMap<Integer, CourseView> courseMap;

        private Integer type;
        private Integer courseId;
        private boolean dateDesc;
        private final String ALLCOURSESOPTION = "All courses";
        private final String ALLTYPESOPTION = "allTypes";
        private String pageTitle = "Notification center";

        public ViewNotificationCenter() {
            super();
            notifications = new ArrayList<>();
            courseIdsNames = new HashMap<>();
            notificationTypes = new HashMap<>();
            courseMap = new HashMap<>();
            dateDesc = true;
        }

        public ArrayList<Notification> getNotifications() {
            return notifications;
        }

        public void setNotifications(ArrayList<Notification> notifications) {
            this.notifications = notifications;
        }

        public HashMap<Integer, String> getNotificationTypes() {
            return notificationTypes;
        }

        public void setNotificationTypes(HashMap<Integer, String> notificationTypes) {
            this.notificationTypes = notificationTypes;
        }

        public HashMap<Integer, String> getCourseIdsNames() {
            return courseIdsNames;
        }

        public void setCourseIdsNames(HashMap<Integer, String> courseIdsNames) {
            this.courseIdsNames = courseIdsNames;
        }

        public Integer getType() {
            return type;
        }

        public void setType(Integer type) {
            this.type = type;
        }

        public Integer getCourseId() {
            return courseId;
        }

        public void setCourseId(Integer courseId) {
            this.courseId = courseId;
        }

        public boolean getDateDesc() {
            return dateDesc;
        }

        public void setDateDesc(boolean dateDesc) {
            this.dateDesc = dateDesc;
        }
        
        public String getPageTitle() {
            return pageTitle;
        }

        public HashMap<Integer, CourseView> getCourseMap() {
            return courseMap;
        }

        @Override
        public String action() throws Exception {
            Connection masterConnection = getMasterConnection();
            int userId = getMasterUserId();

            ArrayList<CourseView> courseView = CourseView.selectAll(masterConnection);
            courseIdsNames.put(-1, ALLCOURSESOPTION);
            for (int i = 0; i < courseView.size(); i++) {
               try {
                   CourseView course = courseView.get(i);
                   UserTaskView.select1ByTaskIdAndUserId(masterConnection, course.getMasterTaskId(), userId);
                   courseIdsNames.put(course.getMasterTaskId(), course.getName());
                   courseMap.put(course.getMasterTaskId(), course);
                }
                // User is not member of the course.
               catch (NoSuchItemException e) {
               }
            }

            notificationTypes.put(-1, ALLTYPESOPTION);
            for  (int i = 0; i < Notification.notificationTypes.size(); i++) {
                notificationTypes.put(i, Notification.notificationTypes.get(i));
            }

            if (courseId != null && courseId < 0) {
                courseId = null;
            }
            if (type != null && type < 0) {
                type = null;
            }

            try {
                notifications = Notification.getNotificationsByFiltersAndMarkAsRead(masterConnection, userId, courseId, notificationTypes.get(type), dateDesc);
            }
            //User haven't received any notifications.
            catch (NoSuchItemException e) {
                return SUCCESS;
            }
            catch (Exception e) {
                throw new WetoActionException("Failed to retrieve notifications");
            }

            return SUCCESS;
        }
    }

    public static class DeleteNotification extends WetoMasterAction {
        private int notificationId;

        public int getNotificationId() {
            return notificationId;
        }

        public void setNotificationId(int notificationId) {
            this.notificationId = notificationId;
        }

        public DeleteNotification() {
            super();
            notificationId = -1;
        }

        @Override
        public String action() throws WetoActionException {
            Connection masterConnection = getMasterConnection();
            int userId = getMasterUserId();

            if(notificationId == -1) {
                throw new WetoActionException("Notification id is missing");
            }

            try {
                Notification notification = new Notification();
                notification.setId(notificationId);
                notification.select(masterConnection);

                if(userId != notification.getUserId()) {
                    throw new WetoActionException("Denied: Notification user id doesn't match the current user");
                }

                notification.delete(masterConnection);
            } catch (WetoActionException e) {
                throw e;
            } catch (NoSuchItemException ignored) {
            } catch (Exception e) {
                throw new WetoActionException("Failed to retrieve or delete notification");
            }

            return SUCCESS;
        }
    }
}