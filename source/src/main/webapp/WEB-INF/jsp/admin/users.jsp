<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">ADMINISTRATION</p><h1>사용자 관리</h1><p>계정을 만들고 Git 작성자와 연결하세요. Git 계정이 일치하는 사용자의 이슈함으로 리뷰를 배정합니다.</p></section>
<section class="card">
  <h2>계정 생성</h2>
  <form method="post" action="${pageContext.request.contextPath}/admin/users" class="form-grid">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <div class="field"><label for="username">아이디</label><input id="username" name="username" required minlength="3" maxlength="80" autocomplete="off" placeholder="예: developer"></div>
    <div class="field"><label for="gitUsername">Git 계정</label><input id="gitUsername" name="gitUsername" required maxlength="100" autocomplete="off" placeholder="@ 없이 Git 사용자명"></div>
    <div class="field"><label for="password">초기 비밀번호</label><input id="password" name="password" type="password" required minlength="12" autocomplete="new-password" aria-describedby="passwordRule"></div>
    <div class="field"><label for="role">권한</label><select id="role" name="role"><option value="USER">일반 사용자</option><option value="ADMIN">관리자</option></select></div>
    <p id="passwordRule" class="muted">비밀번호는 12자 이상, UTF-8 기준 72바이트 이하입니다. 계정 생성은 관리자의 사용 승인으로 처리됩니다.</p>
    <div><button type="submit" class="button primary">계정 생성 및 승인</button></div>
  </form>
</section>
<section class="card">
  <h2>등록된 사용자</h2>
  <div class="table-wrap"><table>
    <thead><tr><th>아이디</th><th>Git 계정</th><th>권한</th><th>상태</th><th>계정 관리</th><th>비밀번호 초기화</th></tr></thead>
    <tbody><c:forEach items="${users}" var="user"><tr>
      <td><strong><c:out value="${user.username}"/></strong></td><td><c:out value="${user.gitUsername}"/></td>
      <td><c:choose><c:when test="${user.admin}">관리자</c:when><c:otherwise>일반 사용자</c:otherwise></c:choose></td>
      <td><span class="badge"><c:choose><c:when test="${user.enabled}">활성</c:when><c:otherwise>비활성</c:otherwise></c:choose></span></td>
      <td><form method="post" action="${pageContext.request.contextPath}/admin/users/${user.id}/enabled">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"><input type="hidden" name="enabled" value="${not user.enabled}">
        <button type="submit" class="button secondary"><c:choose><c:when test="${user.enabled}">비활성화</c:when><c:otherwise>활성화</c:otherwise></c:choose></button>
      </form></td>
      <td><details><summary>초기화</summary><form method="post" action="${pageContext.request.contextPath}/admin/users/${user.id}/password" class="form-stack">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
        <label for="reset-${user.id}">새 비밀번호</label><input id="reset-${user.id}" name="newPassword" type="password" required minlength="12" autocomplete="new-password">
        <p class="muted">이 계정의 기존 로그인 세션을 만료합니다.</p><button type="submit" class="button secondary">비밀번호 초기화</button>
      </form></details></td>
    </tr></c:forEach></tbody>
  </table></div>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
