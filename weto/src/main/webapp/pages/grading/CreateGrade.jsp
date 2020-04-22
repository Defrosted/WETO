<%@ include file="/WEB-INF/taglibs.jsp"%>
<h2><s:text name="grading.header.createGrade" /></h2>
<form action="<s:url action="createGrade" />" method="post">
  <input type="hidden" name="taskId" value="${taskId}" />
  <input type="hidden" name="tabId" value="${tabId}" />
  <input type="hidden" name="dbId" value="${dbId}" />
  <input type="hidden" name="receiverId" value="${receiverId}" />
  <s:if test="submissionId != null">
    <input type="hidden" name="submissionId" value="${submissionId}" />
  </s:if>
  <table>
    <tr>
      <td><strong><s:text name="general.header.reviewer" />:</strong></td>
      <td>
        <select name="reviewerId">
          <s:iterator var="user" value="users">
            <s:if test="#user.userId == reviewerId">
              <option value="${user.userId}" selected="selected">
                ${user.lastName}, ${user.firstName} (${user.loginName})
              </option>
            </s:if>
            <s:else>
              <option value="${user.userId}">
                ${user.lastName}, ${user.firstName} (${user.loginName})
              </option>
            </s:else>
          </s:iterator>
        </select>
      </td>
    </tr>
    <tr>
      <td><strong><s:text name="general.header.receiver" />:</strong></td>
      <td>
        <s:if test="navigator.teacher">
          <s:url action="viewStudent" var="viewStudentUrl">
            <s:param name="taskId" value="%{taskId}" />
            <s:param name="tabId" value="%{tabId}" />
            <s:param name="dbId" value="%{dbId}" />
            <s:param name="studentId" value="%{receiver.userId}" />
          </s:url>
          <s:a href="%{viewStudentUrl}" anchor="%{receiver.userId }">
            ${receiver.lastName}, ${receiver.firstName}
          </s:a>
          (${receiver.loginName})
        </s:if>
        <s:else>
          ${receiver.lastName}, ${receiver.firstName} (${receiver.loginName})
        </s:else>
      </td>
    </tr>
    <tr>
      <td><strong><s:text name="general.header.mark" />:</strong></td>
      <td>
        <input type="number" name="mark" step="${scoring.getProperty("scoreStep", "1")}"
               min="${scoring.getProperty("minScore")}" max="${scoring.getProperty("maxScore")}"
               value="${scoring.getProperty("maxScore")}" />
      </td>
    </tr>
    <tr>
      <td><s:text name="grading.header.withReview" /></td>
      <td>
        <input type="checkbox" name="withReview" value="true" />
      </td>
    </tr>
    <tr>
      <td><s:text name="grading.header.withNotification" /></td>
      <td>
        <input type="checkbox" name="withNotification" value="true" />
      </td>
    </tr>
  </table>
  <input type="submit" value="<s:text name="general.header.create" />" class="linkButton" />
</form>
