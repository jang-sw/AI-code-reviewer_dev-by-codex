<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">ACCOUNT</p><h1>비밀번호 변경</h1><p>변경하면 모든 기기의 기존 로그인 세션이 만료됩니다.</p></section>
<section class="card auth-panel">
  <form method="post" action="${pageContext.request.contextPath}/account/password" class="form-stack">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <label for="currentPassword">현재 비밀번호</label>
    <input id="currentPassword" name="currentPassword" type="password" autocomplete="current-password" required>
    <label for="newPassword">새 비밀번호</label>
    <input id="newPassword" name="newPassword" type="password" autocomplete="new-password" minlength="12" required aria-describedby="passwordRule">
    <p id="passwordRule" class="muted">12자 이상, UTF-8 기준 72바이트 이하. 다른 서비스에서 사용하지 않는 긴 비밀번호를 권장합니다.</p>
    <label for="confirmPassword">새 비밀번호 확인</label>
    <input id="confirmPassword" name="confirmPassword" type="password" autocomplete="new-password" minlength="12" required>
    <button type="submit" class="button primary">비밀번호 변경</button>
  </form>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
