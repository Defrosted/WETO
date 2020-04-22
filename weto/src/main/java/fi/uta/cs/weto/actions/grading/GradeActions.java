package fi.uta.cs.weto.actions.grading;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.uta.cs.sqldatamodel.NoSuchItemException;
import fi.uta.cs.weto.actions.forum.ForumActions.ForumBean;
import fi.uta.cs.weto.db.*;
import fi.uta.cs.weto.model.CSVReview;
import fi.uta.cs.weto.model.ClusterType;
import fi.uta.cs.weto.model.GradeStatus;
import fi.uta.cs.weto.model.GradingModel;
import fi.uta.cs.weto.model.InstructionBean;
import fi.uta.cs.weto.model.LogEvent;
import fi.uta.cs.weto.model.PermissionModel;
import fi.uta.cs.weto.model.PermissionType;
import fi.uta.cs.weto.model.SubmissionModel;
import fi.uta.cs.weto.model.SubmissionStatus;
import fi.uta.cs.weto.model.Tab;
import fi.uta.cs.weto.model.TagType;
import fi.uta.cs.weto.model.WetoActionException;
import fi.uta.cs.weto.model.WetoCourseAction;
import fi.uta.cs.weto.model.WetoTimeStamp;
import fi.uta.cs.weto.util.Email;
import fi.uta.cs.weto.util.WetoUtilities;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document.OutputSettings;
import org.jsoup.safety.Whitelist;

public class GradeActions
{
  public static class View extends WetoCourseAction
  {
    private static Map<Integer, String> statusList;

    private Integer gradeId;
    private Grade grade;
    private Scoring scoring;
    private UserTaskView reviewer;
    private UserTaskView receiver;
    private ArrayList<Tag> reviews;
    private String[] authors;
    private Submission submission;
    private SubmissionStatus[] states;
    private ArrayList<Document> documents;
    private Integer receiverId;
    private Integer submissionId;
    private ArrayList<InstructionBean> reviewInstructions;
    private ArrayList<CSVReview> CSVReviews;
    private HashMap<Integer, String> reviewersMap;
    private Boolean gradingPeriodActive;
    private Boolean challengePeriodActive;

    public View()
    {
      super(Tab.GRADING.getBit(), 0, 0, 0);
    }

    @Override
    public String action() throws Exception
    {
      if(statusList == null)
      {
        statusList = new HashMap<>();
        for(GradeStatus status : GradeStatus.values())
        {
          if(status.getUserCanSet())
          {
            statusList.put(status.getValue(), getText(status.getProperty()));
          }
        }
      }
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      grade = Grade.select1ById(conn, gradeId);
      validateCourseSubtaskId(grade.getTaskId());
      receiverId = grade.getReceiverId();
      Integer reviewerId = grade.getReviewerId();
      // First verify grade viewing rights.
      boolean isTeacher = getNavigator().isTeacher();
      boolean reviewerIsTeacher = false;
      try
      {
        reviewer = UserTaskView.select1ByTaskIdAndUserId(conn, taskId,
                reviewerId);
        reviewerIsTeacher = ClusterType.TEACHERS.getValue().equals(reviewer
                .getClusterType());
      }
      catch(NoSuchItemException e)
      {
      }
      boolean ignoreGroups = false;
      try
      {
        Scoring scoring = Scoring.select1ByTaskId(conn, taskId);
        ignoreGroups = Boolean.parseBoolean(scoring.getProperty("ignoreGroups"));
      }
      catch(NoSuchItemException e)
      {
      }
      final String userIP = getNavigator().getUserIP();
      // Checking time period related rights
      boolean isUserReceiver = SubmissionModel.checkSubmissionOwnership(conn,
              taskId, userId, receiverId, !isTeacher, ignoreGroups) || isTeacher;
      boolean isUserReviewer = SubmissionModel.checkSubmissionOwnership(conn,
              taskId, userId, reviewerId, !isTeacher, ignoreGroups)
              || (isTeacher && !reviewerIsTeacher);
      WetoTimeStamp[] gradingPeriod = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, grade.getTaskId(), PermissionType.GRADING,
              isTeacher);
      int gradingValue = PermissionModel.checkTimeStampLimits(gradingPeriod);
      gradingPeriodActive = (gradingValue == PermissionModel.CURRENT)
              && isUserReviewer;
      WetoTimeStamp[] resultsPeriod = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, taskId, PermissionType.RESULTS);
      int resultsValue = PermissionModel.checkTimeStampLimits(resultsPeriod);
      boolean resultsActive = (resultsValue == PermissionModel.CURRENT)
              && isUserReceiver;
      WetoTimeStamp[] challengeLimits = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, taskId, PermissionType.GRADE_CHALLENGE);
      challengePeriodActive = (PermissionModel.checkTimeStampLimits(
              challengeLimits) == PermissionModel.CURRENT);
      if(!(isUserReceiver || isUserReviewer))
      {
        throw new WetoActionException(getText("general.error.accessDenied"));
      }
      if(!(gradingPeriodActive || resultsActive || challengePeriodActive))
      {
        throw new WetoActionException(getText(
                "general.error.timePeriodNotActive"));
      }
      // Then retrieve the information required by the view: reviewer, receiver,
      // task, scoring rules and reviews.
      // It may be that the user is not allowed to know the reviewer if the
      // reviewer is a student.
      if((reviewer != null) && (reviewer.getClusterType().equals(
              ClusterType.STUDENTS.getValue()) && !haveViewRights(Tab.GRADING
                      .getBit(), isUserReviewer, true)))
      {
        reviewer = null;
      }
      // It also may be that the user is not allowed to know the reviewee.
      if(haveViewRights(Tab.GRADING.getBit(), isUserReceiver, true))
      {
        receiver = UserTaskView.select1ByTaskIdAndUserId(conn, taskId,
                receiverId);
      }
      Task task = getTask();
      scoring = Scoring.select1ByTaskId(conn, taskId);
      reviews = Tag.selectByTaggedIdAndType(conn, grade.getId(), TagType.REVIEW
              .getValue());
      boolean canEdit = haveUpdateRights(Tab.GRADING.getBit(), isUserReviewer,
              true) && (gradingPeriodActive || (challengePeriodActive
              && GradeStatus.CHALLENGED.getValue().equals(grade.getStatus())));
      // For grading, select only updateable reviews.
      if(canEdit)
      {
        ArrayList<Tag> editableReviews = new ArrayList<>();
        for(Tag review : reviews)
        {
          if(haveUpdateRights(Tab.GRADING.getBit(), userId.equals(review
                  .getAuthorId()), true))
          {
            editableReviews.add(review);
          }
        }
        reviews = editableReviews;
      }
      // Select authors that are not anonymous. Also generate CSVreviews.
      authors = new String[reviews.size()];
      reviewersMap = new HashMap<>();
      CSVReviews = new ArrayList<>();
      int i = 0;
      for(Tag review : reviews)
      {
        Integer authorId = review.getAuthorId();
        if(haveViewRights(Tab.GRADING.getBit(), userId.equals(authorId), true))
        {
          UserAccount author = UserAccount.select1ById(conn, authorId);
          authors[i] = author.getLastName() + ", " + author.getFirstName();
          reviewersMap.put(review.getId(), author.getLastName() + ", " + author
                  .getFirstName());
        }
        CSVReviews.add(new CSVReview(review));
        i += 1;
      }
      documents = new ArrayList<>();
      if(task.getHasSubmissions())
      {
        try
        {
          submission = Submission.select1LatestSubmissionByUserIdAndTaskId(conn,
                  receiverId, taskId);
          states = SubmissionStatus.values();
          documents = Document.selectBySubmissionId(conn, submission.getId());
        }
        catch(NoSuchItemException e)
        {
        }
      }
      // Get review instructions
      if(getTask().getHasReviewInstructions())
      {
        this.reviewInstructions = ReviewInstructionsActions
                .getInstructionsBeans(conn, taskId);
      }
      // Add the view grading event to the log.
      if(!getNavigator().isStudentRole())
      {
        new Log(getCourseTaskId(), taskId, userId, LogEvent.VIEW_GRADING
                .getValue(), grade.getId(), null, getRequest().getRemoteAddr())
                .insert(conn);
      }
      if(canEdit)
      {
        return "edit";
      }
      else
      {
        return "view";
      }
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public Grade getGrade()
    {
      return grade;
    }

    public Scoring getScoring()
    {
      return scoring;
    }

    public UserTaskView getReviewer()
    {
      return reviewer;
    }

    public UserTaskView getReceiver()
    {
      return receiver;
    }

    public ArrayList<Tag> getReviews()
    {
      return reviews;
    }

    public String[] getAuthors()
    {
      return authors;
    }

    public Submission getSubmission()
    {
      return submission;
    }

    public ArrayList<Document> getDocuments()
    {
      return documents;
    }

    public SubmissionStatus[] getStates()
    {
      return states;
    }

    public Map<Integer, String> getStatusList()
    {
      return statusList;
    }

    public Integer getAnchorId()
    {
      return receiverId;
    }

    public Integer getSubmissionId()
    {
      return submissionId;
    }

    public void setSubmissionId(Integer submissionId)
    {
      this.submissionId = submissionId;
    }

    public ArrayList<InstructionBean> getReviewInstructions()
    {
      return reviewInstructions;
    }

    public Boolean getHasReviewInstructions()
    {
      return getTask().getHasReviewInstructions();
    }

    public ArrayList<CSVReview> getCSVReviews()
    {
      return CSVReviews;
    }

    public Boolean getGradingPeriodActive()
    {
      return gradingPeriodActive;
    }

    public Boolean getChallengePeriodActive()
    {
      return challengePeriodActive;
    }

    public HashMap<Integer, String> getReviewersMap()
    {
      return reviewersMap;
    }

  }

  public static class Create extends WetoCourseAction
  {
    private Integer receiverId;
    private Integer reviewerId;
    private boolean withReview;
    private boolean withNotification;
    private ArrayList<UserTaskView> users;
    private UserTaskView receiver;
    private Scoring scoring;
    private Float mark;
    private Integer submissionId;

    public Create()
    {
      super(Tab.GRADING.getBit(), 0, 0, 0);
    }

    @Override
    public String action() throws Exception
    {
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      final String userIP = getNavigator().getUserIP();
      // Check that user has rights to update and create grades and that grading
      // is active.
      WetoTimeStamp[] gradingPeriod = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, taskId, PermissionType.GRADING, getNavigator()
                      .isTeacher());
      int gradingValue = PermissionModel.checkTimeStampLimits(gradingPeriod);
      if(!haveUpdateRights(Tab.GRADING.getBit(), false, true)
              || !haveCreateRights(Tab.GRADING.getBit(), false, true))
      {
        throw new WetoActionException(getText("general.error.accessDenied"),
                ACCESS_DENIED);
      }
      else if(gradingValue != PermissionModel.CURRENT)
      {
        throw new WetoActionException(getText(
                "general.error.timePeriodNotActive"));
      }
      if(reviewerId == null)
      {
        reviewerId = userId;
        users = UserTaskView.selectUniqueByTaskId(conn, taskId);
        withReview = false;
        receiver = UserTaskView.select1ByTaskIdAndUserId(conn, taskId,
                receiverId);
        scoring = Scoring.select1ByTaskId(conn, taskId);
        return INPUT;
      }
      else
      {
        Grade grade = new Grade();
        grade.setMark(mark);
        grade.setReceiverId(receiverId);
        grade.setReviewerId(reviewerId);
        grade.setTaskId(taskId);
        if(mark != null)
        {
          grade.setStatus(GradeStatus.VALID.getValue());
        }
        grade.insert(conn);
        if(withReview)
        {
          Tag tag = new Tag();
          tag.setTaggedId(grade.getId());
          tag.setType(TagType.REVIEW.getValue());
          tag.setAuthorId(reviewerId);
          tag.insert(conn);
        }

        if (withNotification) {
          createNotifications();
        }

        // Add the create grade event to the log.
        if(!getNavigator().isStudentRole())
        {
          new Log(getCourseTaskId(), taskId, userId, LogEvent.CREATE_GRADE
                  .getValue(), grade.getId(), null, getRequest().getRemoteAddr())
                  .insert(conn);
        }
        return (submissionId == null) ? SUCCESS : "submission";
      }
    }

    private void createNotifications() {
      Connection masterConnection = getMasterConnection();
      Connection courseConnection = getCourseConnection();

      try {
        // Map values that will be put to the notification template
        HashMap<String, String> valueMap = new HashMap<>();
        valueMap.put("&task;", getTask().getName());
        valueMap.put("&course;", getCourseName());

        String notificationMessage = Notification.getMessageFromTemplate(Notification.GRADE_ADDED, valueMap);

        CourseImplementation masterCourse = CourseImplementation.select1ByDatabaseIdAndCourseTaskId(masterConnection, getDbId(), getCourseTaskId());

        UserAccount user = UserAccount.select1ById(courseConnection, receiverId);
        UserAccount masterUser = UserAccount.select1ByLoginName(masterConnection, user.getLoginName());

        Notification notification = new Notification(masterUser.getId(), masterCourse.getMasterTaskId(), Notification.GRADE_ADDED);
        notification.setMessage(notificationMessage);
        notification.createNotification(masterConnection, courseConnection);
      } catch (Exception ignored) {
      }
    }

    public Integer getReceiverId()
    {
      return receiverId;
    }

    public void setReceiverId(Integer receiverId)
    {
      this.receiverId = receiverId;
    }

    public Integer getReviewerId()
    {
      return reviewerId;
    }

    public void setReviewerId(Integer reviewerId)
    {
      this.reviewerId = reviewerId;
    }

    public boolean isWithReview()
    {
      return withReview;
    }

    public void setWithReview(boolean withReview)
    {
      this.withReview = withReview;
    }

    public boolean isWithNotification()
    {
      return withNotification;
    }

    public void setWithNotification(boolean withNotification)
    {
      this.withNotification = withNotification;
    }

    public ArrayList<UserTaskView> getUsers()
    {
      return users;
    }

    public UserTaskView getReceiver()
    {
      return receiver;
    }

    public Scoring getScoring()
    {
      return scoring;
    }

    public void setMark(Float mark)
    {
      this.mark = mark;
    }

    public Integer getAnchorId()
    {
      return receiverId;
    }

    public Integer getSubmissionId()
    {
      return submissionId;
    }

    public void setSubmissionId(Integer submissionId)
    {
      this.submissionId = submissionId;
    }

  }

  public static class Save extends WetoCourseAction
  {
    private Integer gradeId;
    private Integer receiverId;
    private String markString;
    private String[] markStrings;
    private Integer status;
    private Integer submissionId;
    private Integer tagId;
    private String tagText;
    private String[] tagTexts;

    public Save()
    {
      super(Tab.GRADING.getBit(), 0, 0, 0);
    }

    @Override
    public String action() throws Exception
    {
      Connection conn = getCourseConnection();
      /* -- FIRST UP: CHECKING RIGHTS -- */
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      Grade grade = Grade.select1ById(conn, gradeId);
      Tag reviewTag = null;
      if(tagId != null)
      {
        reviewTag = Tag.select1ById(conn, tagId);
        // Verify that the tag corresponds to this grade
        if(!reviewTag.getTaggedId().equals(grade.getId()))
        {
          throw new WetoActionException(getText("general.error.accessDenied"),
                  ACCESS_DENIED);
        }
      }
      validateCourseSubtaskId(grade.getTaskId());
      final boolean isTeacher = getNavigator().isTeacher();
      final String userIP = getNavigator().getUserIP();
      // Check that user has rights to update this grade and that grading is active
      WetoTimeStamp[] gradingPeriod = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, grade.getTaskId(), PermissionType.GRADING,
              isTeacher);
      WetoTimeStamp[] challengeLimits = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, taskId, PermissionType.GRADE_CHALLENGE);
      boolean challengePeriodActive = (PermissionModel.checkTimeStampLimits(
              challengeLimits) == PermissionModel.CURRENT);
      boolean gradingPeriodActive = (PermissionModel.checkTimeStampLimits(
              gradingPeriod) == PermissionModel.CURRENT);
      Scoring taskScoring = Scoring.select1ByTaskId(conn, taskId);
      boolean ignoreGroups = Boolean.parseBoolean(taskScoring.getProperty(
              "ignoreGroups"));
      ArrayList<Integer> submissionMemberIds = SubmissionModel
              .getSubmissionGroupMemberIds(conn, taskId, userId, ignoreGroups);
      boolean isGradeOwnerOrReviewer = SubmissionModel.checkSubmissionOwnership(
              userId, grade.getReviewerId(), !isTeacher, ignoreGroups,
              submissionMemberIds);
      if(!haveUpdateRights(Tab.GRADING.getBit(), isGradeOwnerOrReviewer, true))
      {
        throw new WetoActionException(getText("general.error.accessDenied"),
                ACCESS_DENIED);
      }
      if(reviewTag != null)
      {
        boolean isReviewOwnerOrReviewer = SubmissionModel
                .checkSubmissionOwnership(userId, reviewTag.getAuthorId(),
                        !isTeacher, ignoreGroups, submissionMemberIds);
        if(!haveUpdateRights(Tab.GRADING.getBit(), isReviewOwnerOrReviewer, true))
        {
          throw new WetoActionException(getText("general.error.accessDenied"),
                  ACCESS_DENIED);
        }
      }
      if(!(gradingPeriodActive || (challengePeriodActive
              && GradeStatus.CHALLENGED.getValue().equals(grade.getStatus()))))
      {
        throw new WetoActionException(getText(
                "general.error.timePeriodNotActive"));
      }
      receiverId = grade.getReceiverId();
      /* -- SECOND PHASE: Setting new values for Grade object --*/
      // Students Cannot explicitly change grade status.
      if(getNavigator().isStudent())
      {
        status = null;
        grade.setReviewerId(userId);
      }
      if((markStrings != null) || (markString != null))
      {
        float minScore = Float
                .parseFloat(taskScoring.getProperty("minScore", "0"));
        float maxScore = Float
                .parseFloat(taskScoring.getProperty("maxScore", "0"));
        Float mark = null;
        // In case of review-based grading, mark must be calculated from marks array
        if(markStrings != null)
        {
          for(int i = 0; i < markStrings.length; ++i)
          {
            if((markStrings[i] != null) && !markStrings[i].isEmpty())
            {
              float partMark = Float.parseFloat(markStrings[i]);
              if(mark != null)
              {
                mark += partMark;
              }
              else
              {
                mark = partMark;
              }
            }
          }
          // Add other review-based marks, if any exist.
          ArrayList<Tag> reviewTags = Tag.selectByTaggedIdAndType(conn, gradeId,
                  TagType.REVIEW.getValue());
          if(!reviewTags.isEmpty())
          {
            ArrayList<String> reviewTexts = new ArrayList<>();
            ArrayList<String> markTexts = new ArrayList<>();
            for(Tag review : reviewTags)
            {
              if(!review.getId().equals(tagId))
              {
                CSVReview.readCSV(review.getText(), reviewTexts, markTexts);
              }
            }
            for(String markText : markTexts)
            {
              if((markText != null) && !markText.isEmpty())
              {
                float partMark = Float.parseFloat(markText);
                if(mark != null)
                {
                  mark += partMark;
                }
                else
                {
                  mark = partMark;
                }
              }
            }
          }
        }
        else if(!markString.isEmpty())
        {
          mark = Float.valueOf(markString);
        }
        // Check that mark is in the correct range.
        if(mark != null)
        {
          if(mark < minScore)
          {
            addActionError(getText("grading.error.markTooLow", new String[]
            {
              mark.toString(),
              new Float(minScore).toString()
            }));
            mark = minScore;
          }
          else if(mark > maxScore)
          {
            addActionError(getText("grading.error.markTooHigh", new String[]
            {
              mark.toString(),
              new Float(maxScore).toString()
            }));
            mark = maxScore;
          }
        }
        if(!GradeStatus.CHALLENGED.getValue().equals(grade.getStatus()))
        {
          if(mark == null)
          {
            status = GradeStatus.UNSPECIFIED.getValue();
          }
          else if(GradeStatus.UNSPECIFIED.getValue().equals(grade.getStatus())
                  && ((status == null) || status.equals(GradeStatus.UNSPECIFIED
                          .getValue())))
          {
            status = GradeStatus.VALID.getValue();
          }
        }
        grade.setMark(mark);
      }
      if(status != null)
      {
        grade.setStatus(status);
      }
      grade.update(conn);
      if(reviewTag != null)
      {
        /* -- THIRD PHASE: Saving the new review text(s) and submark(s). -- */
        // Decode the review tag
        CSVReview review = CSVReview.read(reviewTag);
        // Get text and marks
        ArrayList<String> newReviewTexts = new ArrayList<>();
        if(tagText != null)
        {
          newReviewTexts.add(this.tagText);
        }
        else if(tagTexts != null)
        {
          for(int i = 0; i < tagTexts.length; ++i)
          {
            newReviewTexts.add(tagTexts[i]);
          }
        }
        ArrayList<String> newMarksList = new ArrayList<>();
        if(markStrings != null)
        {
          for(int i = 0; i < markStrings.length; ++i)
          {
            newMarksList.add(markStrings[i]);
          }
        }
        else
        {
          newMarksList.add(markString);
        }
        review.setTexts(newReviewTexts);
        review.setMarks(newMarksList);
        review.update(conn, reviewTag);
        addActionMessage(getText("grading.message.reviewAndGradeSaved"));
      }
      else
      {
        addActionMessage(getText("grading.message.gradeSaved"));
      }
      // Add the update grade event to the log.
      if(!getNavigator().isStudentRole())
      {
        new Log(getCourseTaskId(), taskId, userId, LogEvent.UPDATE_GRADE
                .getValue(), grade.getId(), null, getRequest().getRemoteAddr())
                .insert(conn);
      }
      return (submissionId == null) ? SUCCESS : "submission";
    }

    public void setTagText(String tagText)
    {
      this.tagText = (tagText != null) ? Jsoup.clean(tagText.replaceAll("\r\n",
              "\n"), "",
              Whitelist.basicWithImages(),
              new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
                             : null;
    }

    public void setTagTexts(String[] tagTexts)
    {
      for(int i = 0; i < tagTexts.length; ++i)
      {
        tagTexts[i] = (tagTexts[i] != null) ? Jsoup.clean(tagTexts[i]
                .replaceAll("\r\n", "\n"), "",
                Whitelist.basicWithImages(),
                new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
                      : null;
      }

      this.tagTexts = tagTexts;
    }

    public void setMark(String mark)
    {
      this.markString = mark;
    }

    public void setMarks(String[] marks)
    {
      this.markStrings = marks;
    }

    public void setStatus(Integer status)
    {
      this.status = status;
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public Integer getGradeId()
    {
      return gradeId;
    }

    public void setTagId(Integer tagId)
    {
      this.tagId = tagId;
    }

    public void setSubmissionId(Integer submissionId)
    {
      this.submissionId = submissionId;
    }

    public Integer getReceiverId()
    {
      return receiverId;
    }

  }

  public static class Delete extends WetoCourseAction
  {
    private Integer gradeId;
    private Grade grade;
    private UserTaskView reviewer;
    private UserTaskView receiver;
    private ArrayList<Tag> reviews;
    private String[] authors;
    private Submission submission;
    private ArrayList<Document> documents;
    private Integer receiverId;

    public Delete()
    {
      super(Tab.GRADING.getBit(), 0, 0, Tab.GRADING.getBit());
    }

    @Override
    public String action() throws Exception
    {
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      grade = Grade.select1ById(conn, gradeId);
      validateCourseSubtaskId(grade.getTaskId());
      Integer reviewerId = grade.getReviewerId();
      final boolean isTeacher = getNavigator().isTeacher();
      final String userIP = getNavigator().getUserIP();
      // Check that user has rights to delete this grade and that
      // grading is active
      WetoTimeStamp[] gradingPeriod = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, grade.getTaskId(), PermissionType.GRADING,
              isTeacher);
      int gradingValue = PermissionModel.checkTimeStampLimits(gradingPeriod);
      boolean ignoreGroups = false;
      try
      {
        Scoring scoring = Scoring.select1ByTaskId(conn, getTaskId());
        ignoreGroups = Boolean.parseBoolean(scoring.getProperty("ignoreGroups"));
      }
      catch(NoSuchItemException e)
      {
      }
      boolean isUserReviewer = SubmissionModel.checkSubmissionOwnership(conn,
              taskId, userId, grade.getReviewerId(), !isTeacher, ignoreGroups);
      if(!haveDeleteRights(Tab.GRADING.getBit(), isUserReviewer, true))
      {
        throw new WetoActionException(getText("general.error.accessDenied"),
                ACCESS_DENIED);
      }
      else if(gradingValue != PermissionModel.CURRENT)
      {
        throw new WetoActionException(getText(
                "general.error.timePeriodNotActive"));
      }
      if(reviewerId != null)
      {
        reviewer = UserTaskView.select1ByTaskIdAndUserId(conn, taskId,
                reviewerId);
      }
      receiverId = grade.getReceiverId();
      receiver = UserTaskView.select1ByTaskIdAndUserId(conn, taskId, receiverId);
      reviews = Tag.selectByTaggedIdAndType(conn, grade.getId(), TagType.REVIEW
              .getValue());
      // Select authors that are not anonymous.
      authors = new String[reviews.size()];
      int i = 0;
      for(Tag review : reviews)
      {
        Integer authorId = review.getAuthorId();
        UserAccount author = UserAccount.select1ById(conn,
                authorId);
        authors[i] = author.getLastName() + ", " + author.getFirstName();
        i += 1;
      }
      documents = new ArrayList<>();
      Task task = getTask();
      if(task.getHasSubmissions())
      {
        try
        {
          submission = Submission.select1LatestSubmissionByUserIdAndTaskId(conn,
                  receiverId, taskId);
          documents = Document.selectBySubmissionId(conn, submission.getId());
        }
        catch(NoSuchItemException e)
        {
        }
      }
      return INPUT;
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public Grade getGrade()
    {
      return grade;
    }

    public UserTaskView getReviewer()
    {
      return reviewer;
    }

    public UserTaskView getReceiver()
    {
      return receiver;
    }

    public ArrayList<Tag> getReviews()
    {
      return reviews;
    }

    public String[] getAuthors()
    {
      return authors;
    }

    public Submission getSubmission()
    {
      return submission;
    }

    public ArrayList<Document> getDocuments()
    {
      return documents;
    }

    public Integer getAnchorId()
    {
      return receiverId;
    }

  }

  public static class CommitDelete extends WetoCourseAction
  {
    private Integer gradeId;
    private Integer receiverId;

    public CommitDelete()
    {
      super(Tab.GRADING.getBit(), 0, 0, Tab.GRADING.getBit());
    }

    @Override
    public String action() throws Exception
    {
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      Grade grade = Grade.select1ById(conn, gradeId);
      validateCourseSubtaskId(grade.getTaskId());
      receiverId = grade.getReceiverId();
      Integer reviewerId = grade.getReviewerId();
      final String userIP = getNavigator().getUserIP();
      // Check that user has rights to delete this grade and that
      // grading is active
      WetoTimeStamp[] gradingPeriod = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, grade.getTaskId(), PermissionType.GRADING,
              getNavigator().isTeacher());
      int gradingValue = PermissionModel.checkTimeStampLimits(gradingPeriod);
      if(!haveDeleteRights(Tab.GRADING.getBit(), userId.equals(reviewerId), true))
      {
        throw new WetoActionException(getText("general.error.accessDenied"),
                ACCESS_DENIED);
      }
      else if(gradingValue != PermissionModel.CURRENT)
      {
        throw new WetoActionException(getText(
                "general.error.timePeriodNotActive"));
      }
      GradingModel.deleteGrade(conn, grade);
      GradingModel.recalculateStudentGrades(conn, grade.getTaskId(), grade
              .getReceiverId());
      addActionMessage(getText("grading.message.gradeDeleted"));
      // Add the delete grade event to the log.
      if(!getNavigator().isStudentRole())
      {
        new Log(getCourseTaskId(), taskId, userId, LogEvent.DELETE_GRADE
                .getValue(), grade.getId(), null, getRequest().getRemoteAddr())
                .insert(conn);
      }
      return SUCCESS;
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public Integer getAnchorId()
    {
      return receiverId;
    }

  }

  public static class CreateReview extends WetoCourseAction
  {
    private Integer gradeId;
    private Integer tagId;
    private Integer submissionId;

    public CreateReview()
    {
      super(Tab.GRADING.getBit(), 0, 0, 0);
    }

    @Override
    public String action() throws Exception
    {
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      Grade grade = Grade.select1ById(conn, gradeId);
      validateCourseSubtaskId(grade.getTaskId());
      final String userIP = getNavigator().getUserIP();
      // Check that user has rights to update this grade and that
      // grading is active
      WetoTimeStamp[] gradingPeriod = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, grade.getTaskId(), PermissionType.GRADING,
              getNavigator().isTeacher());
      int gradingValue = PermissionModel.checkTimeStampLimits(gradingPeriod);
      if(!haveUpdateRights(Tab.GRADING.getBit(), userId.equals(grade
              .getReviewerId()), true))
      {
        throw new WetoActionException(getText("general.error.accessDenied"),
                ACCESS_DENIED);
      }
      else if(gradingValue != PermissionModel.CURRENT)
      {
        throw new WetoActionException(getText(
                "general.error.timePeriodNotActive"));
      }
      Tag review = new Tag();
      review.setTaggedId(gradeId);
      review.setType(TagType.REVIEW.getValue());
      review.setAuthorId(userId);
      review.insert(conn);
      tagId = review.getId();
      addActionMessage(getText("grading.message.reviewCreated"));
      // Add the update grade event to the log.
      if(!getNavigator().isStudentRole())
      {
        new Log(getCourseTaskId(), taskId, userId, LogEvent.UPDATE_GRADE
                .getValue(), grade.getId(), null, userIP).insert(conn);
      }
      return (submissionId == null) ? SUCCESS : "submission";
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public Integer getGradeId()
    {
      return gradeId;
    }

    public Integer getTagId()
    {
      return tagId;
    }

    public Integer getSubmissionId()
    {
      return submissionId;
    }

    public void setSubmissionId(Integer submissionId)
    {
      this.submissionId = submissionId;
    }

  }

  public static class DeleteReview extends WetoCourseAction
  {
    private Integer gradeId;
    private Integer tagId;
    private Integer submissionId;

    public DeleteReview()
    {
      super(Tab.GRADING.getBit(), 0, 0, 0);
    }

    @Override
    public String action() throws Exception
    {
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      Grade grade = Grade.select1ById(conn, gradeId);
      validateCourseSubtaskId(grade.getTaskId());
      final String userIP = getNavigator().getUserIP();
      // Check that user has rights to update this grade and that
      // grading is active
      WetoTimeStamp[] gradingPeriod = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, grade.getTaskId(), PermissionType.GRADING,
              getNavigator().isTeacher());
      int gradingValue = PermissionModel.checkTimeStampLimits(gradingPeriod);
      if(!haveUpdateRights(Tab.GRADING.getBit(), userId.equals(grade
              .getReviewerId()), true))
      {
        throw new WetoActionException(getText("general.error.accessDenied"),
                ACCESS_DENIED);
      }
      else if(gradingValue != PermissionModel.CURRENT)
      {
        throw new WetoActionException(getText(
                "general.error.timePeriodNotActive"));
      }
      Tag review = Tag.select1ById(conn, tagId);
      if(review.getTaggedId().equals(gradeId))
      {
        review.delete(conn);
      }
      addActionMessage(getText("grading.message.reviewDeleted"));
      // Add the update grade event to the log.
      if(!getNavigator().isStudentRole())
      {
        new Log(getCourseTaskId(), taskId, userId, LogEvent.UPDATE_GRADE
                .getValue(), grade.getId(), null, userIP).insert(conn);
      }
      return (submissionId == null) ? SUCCESS : "submission";
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public Integer getGradeId()
    {
      return gradeId;
    }

    public void setTagId(Integer tagId)
    {
      this.tagId = tagId;
    }

    public Integer getSubmissionId()
    {
      return submissionId;
    }

    public void setSubmissionId(Integer submissionId)
    {
      this.submissionId = submissionId;
    }

  }

  private static final String anonymousName = WetoUtilities.getMessageResource(
          "general.header.student");

  public static class Challenge extends WetoCourseAction
  {
    private Integer gradeId;

    public Challenge()
    {
      super(Tab.GRADING.getBit(), 0, 0, 0);
    }

    @Override
    public String action() throws Exception
    {
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      // Check rights for grade challenge
      Grade grade = Grade.select1ById(conn, gradeId);
      validateCourseSubtaskId(grade.getTaskId());
      Integer receiverId = grade.getReceiverId();
      // First verify grade viewing rights.
      boolean isTeacher = getNavigator().isTeacher();
      boolean reviewerIsTeacher = false;
      boolean ignoreGroups = false;
      try
      {
        Scoring scoring = Scoring.select1ByTaskId(conn, taskId);
        ignoreGroups = Boolean.parseBoolean(scoring.getProperty("ignoreGroups"));
      }
      catch(NoSuchItemException e)
      {
      }
      ArrayList<Integer> submissionMemberIds = SubmissionModel
              .getSubmissionGroupMemberIds(conn, taskId, userId, ignoreGroups);
      boolean isUserReceiver = SubmissionModel.checkSubmissionOwnership(userId,
              receiverId, !isTeacher, ignoreGroups, submissionMemberIds);
      final String userIP = getNavigator().getUserIP();
      WetoTimeStamp[] challengeLimits = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, taskId, PermissionType.GRADE_CHALLENGE);
      boolean challengePeriodActive = (PermissionModel.checkTimeStampLimits(
              challengeLimits) == PermissionModel.CURRENT);
      if(!isUserReceiver && !isTeacher)
      {
        throw new WetoActionException(getText("general.error.accessDenied"));
      }
      if(!challengePeriodActive && !isTeacher)
      {
        throw new WetoActionException(getText(
                "general.error.timePeriodNotActive"));
      }
      grade.setStatus(GradeStatus.CHALLENGED.getValue());
      grade.update(conn);
      if(!getNavigator().isStudentRole())
      {
        Integer reviewerId = grade.getReviewerId();
        UserTaskView reviewer = UserTaskView.select1ByTaskIdAndUserId(conn,
                taskId, reviewerId);
        Email.scheduleEmail(reviewer.getLoginName(), grade.getId()
                .toString(), reviewer.getEmail(), getText(
                        "weto.header.emailSubject"), getText(
                        "weto.message.emailHead") + getText(
                        "grading.message.gradeChallenged", new String[]
                        {
                          getTask().getName(), getCourseName()
                        }));
      }
      return SUCCESS;
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public Integer getGradeId()
    {
      return gradeId;
    }

  }

  public static class Accept extends WetoCourseAction
  {
    private Integer gradeId;

    public Accept()
    {
      super(Tab.GRADING.getBit(), 0, 0, 0);
    }

    @Override
    public String action() throws Exception
    {
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      // Check rights for accepting grade
      Grade grade = Grade.select1ById(conn, gradeId);
      validateCourseSubtaskId(grade.getTaskId());
      Integer receiverId = grade.getReceiverId();
      // First verify grade viewing rights.
      boolean isTeacher = getNavigator().isTeacher();
      boolean ignoreGroups = false;
      try
      {
        Scoring scoring = Scoring.select1ByTaskId(conn, taskId);
        ignoreGroups = Boolean.parseBoolean(scoring.getProperty("ignoreGroups"));
      }
      catch(NoSuchItemException e)
      {
      }
      ArrayList<Integer> submissionMemberIds = SubmissionModel
              .getSubmissionGroupMemberIds(conn, taskId, userId, ignoreGroups);
      boolean isUserReceiver = SubmissionModel.checkSubmissionOwnership(userId,
              receiverId, !isTeacher, ignoreGroups, submissionMemberIds);
      if(!isUserReceiver)
      {
        throw new WetoActionException(getText("general.error.accessDenied"));
      }
      grade.setStatus(GradeStatus.VALID.getValue());
      grade.update(conn);
      if(!getNavigator().isStudentRole())
      {
        Integer reviewerId = grade.getReviewerId();
        UserTaskView reviewer = UserTaskView.select1ByTaskIdAndUserId(conn,
                taskId, reviewerId);
        Email.scheduleEmail(reviewer.getLoginName(), grade.getId()
                .toString(), reviewer.getEmail(), getText(
                        "weto.header.emailSubject"), getText(
                        "weto.message.emailHead") + getText(
                        "grading.message.challengedGradeAccepted",
                        new String[]
                        {
                          getTask().getName(), getCourseName()
                        }));
      }
      return SUCCESS;
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public Integer getGradeId()
    {
      return gradeId;
    }

  }

  public static class Discuss extends WetoCourseAction
  {
    private Integer gradeId;
    private ArrayList<ForumBean> messageBeans = new ArrayList<>();
    private boolean challengePeriodActive;
    private String[] challengePeriod;

    public Discuss()
    {
      super(Tab.GRADING.getBit(), 0, 0, 0);
    }

    @Override
    public String action() throws Exception
    {
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      // Check rights for grade discussion
      Grade grade = Grade.select1ById(conn, gradeId);
      validateCourseSubtaskId(grade.getTaskId());
      Integer receiverId = grade.getReceiverId();
      Integer reviewerId = grade.getReviewerId();
      boolean isTeacher = getNavigator().isTeacher();
      boolean reviewerIsTeacher = false;
      UserTaskView reviewer = UserTaskView
              .select1ByTaskIdAndUserId(conn, taskId, reviewerId);
      reviewerIsTeacher = ClusterType.TEACHERS.getValue().equals(reviewer
              .getClusterType());
      boolean ignoreGroups = false;
      try
      {
        Scoring scoring = Scoring.select1ByTaskId(conn, taskId);
        ignoreGroups = Boolean.parseBoolean(scoring.getProperty("ignoreGroups"));
      }
      catch(NoSuchItemException e)
      {
      }
      ArrayList<Integer> submissionMemberIds = SubmissionModel
              .getSubmissionGroupMemberIds(conn, taskId, userId, ignoreGroups);
      boolean isUserReceiver = SubmissionModel.checkSubmissionOwnership(userId,
              receiverId, !isTeacher, ignoreGroups, submissionMemberIds);
      boolean isUserReviewer = SubmissionModel.checkSubmissionOwnership(userId,
              reviewerId, !isTeacher, ignoreGroups, submissionMemberIds)
              || (isTeacher && !reviewerIsTeacher);
      final String userIP = getNavigator().getUserIP();
      WetoTimeStamp[] challengeLimits = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, taskId, PermissionType.GRADE_CHALLENGE);
      challengePeriodActive = (PermissionModel.checkTimeStampLimits(
              challengeLimits) == PermissionModel.CURRENT);
      challengePeriod = WetoTimeStamp.limitsToStrings(challengeLimits);
      if(!(isUserReceiver || isUserReviewer))
      {
        throw new WetoActionException(getText("general.error.accessDenied"));
      }
      ArrayList<TagView> messageTags = TagView
              .selectByTaggedIdAndStatusAndType(conn, taskId, gradeId,
                      TagType.GRADE_DISCUSSION.getValue());
      messageTags.sort(new Comparator<TagView>()
      {
        @Override
        public int compare(TagView a, TagView b)
        {
          return Integer.compare(a.getRank(), b.getRank());
        }

      });
      HashSet<Integer> teacherIdSet = new HashSet<>();
      for(UserTaskView teacher : UserTaskView.selectByTaskIdAndClusterType(
              conn, taskId, ClusterType.TEACHERS.getValue()))
      {
        teacherIdSet.add(teacher.getUserId());
      }
      HashMap<Integer, Integer> anonymousIdMap = new HashMap<>();
      int rank = 0;
      for(TagView tag : messageTags)
      {
        Integer authorId = tag.getAuthorId();
        String author = null;
        if(!(teacherIdSet.contains(authorId) || submissionMemberIds.contains(
                authorId)))
        {
          Integer anonymousId = anonymousIdMap.get(authorId);
          if(anonymousId == null)
          {
            anonymousId = anonymousIdMap.size() + 1;
            anonymousIdMap.put(authorId, anonymousId);
          }
          // Make students anonymous to each other
          author = anonymousName + " " + anonymousId.toString();
        }
        else
        {
          author = tag.getFirstName() + " " + tag.getLastName();
        }
        JsonObject messageJson = new JsonParser().parse(tag.getText())
                .getAsJsonObject();
        messageBeans.add(new ForumBean(tag.getId(), rank++, "", messageJson
                .get("text").getAsString(), author, authorId,
                new WetoTimeStamp(tag.getTimeStamp()).toString()));
      }
      if(!getNavigator().isStudentRole())
      {
        new Log(getCourseTaskId(), taskId, userId,
                LogEvent.VIEW_GRADE_DISCUSSION.getValue(), grade.getId(), null,
                userIP).insert(conn);
        Email.cancelEmail(getNavigator().getUser().getLoginName(), grade.getId()
                .toString());
      }
      return SUCCESS;
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public Integer getGradeId()
    {
      return gradeId;
    }

    public ArrayList<ForumBean> getMessageBeans()
    {
      return messageBeans;
    }

    public boolean isChallengePeriodActive()
    {
      return challengePeriodActive;
    }

    public String[] getChallengePeriod()
    {
      return challengePeriod;
    }

  }

  public static class AddDiscussionMessage extends WetoCourseAction
  {
    private Integer gradeId;
    private String messageText;

    public AddDiscussionMessage()
    {
      super(Tab.GRADING.getBit(), 0, 0, 0);
    }

    @Override
    public String action() throws Exception
    {
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      Integer authorId = getCourseUserId();
      Grade grade = Grade.select1ById(conn, gradeId);
      validateCourseSubtaskId(grade.getTaskId());
      Integer receiverId = grade.getReceiverId();
      Integer reviewerId = grade.getReviewerId();
      // First verify grade viewing rights.
      boolean isTeacher = getNavigator().isTeacher();
      boolean reviewerIsTeacher = false;
      UserTaskView reviewer = UserTaskView
              .select1ByTaskIdAndUserId(conn, taskId, reviewerId);
      reviewerIsTeacher = ClusterType.TEACHERS.getValue().equals(reviewer
              .getClusterType());
      boolean ignoreGroups = false;
      try
      {
        Scoring scoring = Scoring.select1ByTaskId(conn, taskId);
        ignoreGroups = Boolean.parseBoolean(scoring.getProperty("ignoreGroups"));
      }
      catch(NoSuchItemException e)
      {
      }
      boolean isUserReceiver = SubmissionModel.checkSubmissionOwnership(conn,
              taskId, userId, receiverId, !isTeacher, ignoreGroups);
      boolean isUserReviewer = SubmissionModel.checkSubmissionOwnership(conn,
              taskId, userId, reviewerId, !isTeacher, ignoreGroups)
              || (isTeacher && !reviewerIsTeacher);
      final String userIP = getNavigator().getUserIP();
      WetoTimeStamp[] challengeLimits = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, taskId, PermissionType.GRADE_CHALLENGE);
      boolean challengePeriodActive = (PermissionModel.checkTimeStampLimits(
              challengeLimits) == PermissionModel.CURRENT);
      if(!(isUserReceiver || isUserReviewer))
      {
        throw new WetoActionException(getText("general.error.accessDenied"));
      }
      if(!challengePeriodActive)
      {
        throw new WetoActionException(getText(
                "general.error.timePeriodNotActive"));
      }
      // Create message.
      JsonObject messageJson = new JsonObject();
      messageJson.addProperty("text", messageText);
      Tag tag = new Tag();
      tag.setText(messageJson.toString());
      tag.setTaggedId(taskId);
      tag.setStatus(gradeId);
      tag.setAuthorId(authorId);
      tag.setType(TagType.GRADE_DISCUSSION.getValue());
      tag.setRank(TagView
              .selectByTaggedIdAndStatusAndType(conn, taskId, gradeId,
                      TagType.GRADE_DISCUSSION.getValue()).size());
      tag.insert(conn);
      addActionMessage(getText("forum.message.messageAdded"));
      if(!getNavigator().isStudentRole())
      {
        new Log(getCourseTaskId(), taskId, userId,
                LogEvent.UPDATE_GRADE_DISCUSSION.getValue(), tag.getId(), null,
                userIP).insert(conn);
        String loginName;
        String email;
        if(isUserReceiver)
        {
          loginName = reviewer.getLoginName();
          email = reviewer.getEmail();
        }
        else
        {
          UserAccount receiver = UserAccount.select1ById(conn, receiverId);
          loginName = receiver.getLoginName();
          email = receiver.getEmail();
        }
        Email.scheduleEmail(loginName, grade.getId().toString(), email,
                getText("weto.header.emailSubject"), getText(
                "weto.message.emailHead") + getText(
                        "grading.message.newGradeDiscussionMessage",
                        new String[]
                        {
                          getTask().getName(), getCourseName()
                        }));
      }
      return SUCCESS;
    }

    public Integer getGradeId()
    {
      return gradeId;
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public void setMessageText(String messageText)
    {
      this.messageText = (messageText != null) ? Jsoup.clean(messageText
              .replaceAll("\r\n", "\n"), "", Whitelist.basicWithImages(),
              new OutputSettings().prettyPrint(false)) : "";
    }

  }

  public static class EditDiscussionMessage extends WetoCourseAction
  {
    private Integer gradeId;
    private Integer messageId;
    private String messageText;
    private boolean commitSave;
    private boolean commitDelete;

    public EditDiscussionMessage()
    {
      super(Tab.GRADING.getBit(), 0, 0, 0);
    }

    @Override
    public String action() throws Exception
    {
      String result = INPUT;
      Connection conn = getCourseConnection();
      Integer taskId = getTaskId();
      Integer userId = getCourseUserId();
      Tag messageTag = Tag.select1ById(conn, messageId);
      // Check rights for editing the message
      if(!(getNavigator().isTeacher() || userId.equals(messageTag.getAuthorId())))
      {
        throw new WetoActionException(getText("ACCESS_DENIED"));
      }
      final String userIP = getNavigator().getUserIP();
      WetoTimeStamp[] challengeLimits = PermissionModel.getTimeStampLimits(conn,
              userIP, userId, taskId, PermissionType.GRADE_CHALLENGE);
      if(PermissionModel.checkTimeStampLimits(challengeLimits)
              != PermissionModel.CURRENT)
      {
        throw new WetoActionException(getText(
                "general.error.timePeriodNotActive"));
      }
      JsonObject messageJson = new JsonParser().parse(messageTag.getText())
              .getAsJsonObject();
      if(commitSave)
      {
        messageJson.addProperty("text", messageText);
        messageTag.setText(messageJson.toString());
        messageTag.update(conn);
        addActionMessage(getText("forum.message.messageSaved"));
        if(!getNavigator().isStudentRole())
        {
          new Log(getCourseTaskId(), taskId, userId,
                  LogEvent.UPDATE_GRADE_DISCUSSION.getValue(), messageTag
                  .getId(), null, userIP).insert(conn);
        }
        result = SUCCESS;
      }
      else if(commitDelete)
      {
        if(!getNavigator().isTeacher())
        {
          throw new WetoActionException(getText("ACCESS_DENIED"));
        }
        addActionMessage(getText("forum.message.messageDeleted"));
        messageTag.delete(conn);
        result = SUCCESS;
      }
      else
      {
        messageText = messageJson.getAsJsonPrimitive("text").getAsString();
      }
      return result;
    }

    public Integer getGradeId()
    {
      return gradeId;
    }

    public void setGradeId(Integer gradeId)
    {
      this.gradeId = gradeId;
    }

    public Integer getMessageId()
    {
      return messageId;
    }

    public void setMessageId(Integer messageId)
    {
      this.messageId = messageId;
    }

    public String getMessageText()
    {
      return messageText;
    }

    public void setMessageText(String messageText)
    {
      this.messageText = (messageText != null) ? Jsoup.clean(messageText
              .replaceAll("\r\n", "\n"), "", Whitelist.basicWithImages(),
              new OutputSettings().prettyPrint(false)) : "";
    }

    public void setCommitSave(boolean commitSave)
    {
      this.commitSave = commitSave;
    }

    public void setCommitDelete(boolean commitDelete)
    {
      this.commitDelete = commitDelete;
    }

  }
}
