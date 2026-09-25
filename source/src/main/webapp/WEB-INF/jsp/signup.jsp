<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="auth-panel card">
  <p class="eyebrow">JOIN CODE REVIEWER</p><h1>회원가입</h1>
  <p>아이디, 비밀번호, Git 계정으로 가입을 요청하세요. 관리자가 승인한 뒤 로그인할 수 있습니다.</p>
  <c:choose><c:when test="${param.submitted != null}">
    <div class="notice success" role="status">가입 요청을 확인했습니다. 신규 계정은 관리자 승인 후 사용할 수 있습니다. 이미 같은 아이디나 Git 계정으로 가입했다면 기존 계정의 승인 상태를 관리자에게 확인해 주세요.</div>
    <p>승인 대기 중에는 로그인할 수 없습니다. 비밀번호를 다른 사람에게 전달하지 마세요.</p>
  </c:when><c:otherwise>
    <c:url var="signupAction" value="/signup"/>
    <form method="post" action="<c:out value='${signupAction}'/>" class="form-stack">
      <input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>">
      <label for="username">아이디</label><input id="username" name="username" required minlength="3" maxlength="80" value="<c:out value='${signupForm.username}'/>" autocomplete="username" aria-describedby="usernameRule">
      <p id="usernameRule" class="muted">영문, 숫자, 점, 밑줄, 하이픈으로 3~80자. 대소문자는 구분하지 않습니다.</p>
      <label for="gitUsername">Git 계정</label><input id="gitUsername" name="gitUsername" required maxlength="100" value="<c:out value='${signupForm.gitUsername}'/>" autocomplete="off" placeholder="@ 없이 Git 사용자명">
      <p class="muted">Git 작성자와 이슈 담당자를 연결하는 데 사용합니다. Git 서비스 비밀번호나 토큰을 입력하지 마세요.</p>
      <label for="password">비밀번호</label><input id="password" name="password" type="password" required minlength="12" autocomplete="new-password" aria-describedby="passwordRule">
      <p id="passwordRule" class="muted">12자 이상, UTF-8 기준 72바이트 이하. 다른 서비스와 다른 비밀번호를 사용하세요.</p>
      <label for="confirmPassword">비밀번호 확인</label><input id="confirmPassword" name="confirmPassword" type="password" required minlength="12" autocomplete="new-password">
      <button type="submit" class="button primary">가입 승인 요청</button>
    </form>
  </c:otherwise></c:choose>
  <p><a href="<c:url value='/login'/>">로그인으로 돌아가기</a></p>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
