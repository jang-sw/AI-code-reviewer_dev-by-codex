<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="auth-panel card">
  <p class="eyebrow">AI CODE REVIEWER</p>
  <h1>로그인</h1>
  <p class="muted">등록한 저장소의 변경 이력과 나에게 배정된 리뷰를 확인하세요.</p>
  <c:if test="${param.error != null}"><div class="alert error" role="alert">아이디 또는 비밀번호가 일치하지 않거나 사용할 수 없는 계정입니다.</div></c:if>
  <c:if test="${param.expired != null}"><div class="alert" role="status">계정 정보가 변경되어 세션이 만료되었습니다. 다시 로그인해 주세요.</div></c:if>
  <c:if test="${param.logout != null}"><div class="alert" role="status">로그아웃했습니다.</div></c:if>
  <c:if test="${param.changed != null}"><div class="alert success" role="status">비밀번호를 변경했습니다. 새 비밀번호로 로그인해 주세요.</div></c:if>
  <form method="post" action="${pageContext.request.contextPath}/login" class="form-stack">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <label for="username">아이디</label>
    <input id="username" name="username" autocomplete="username" required maxlength="80" autofocus>
    <label for="password">비밀번호</label>
    <input id="password" name="password" type="password" autocomplete="current-password" required>
    <button type="submit" class="button primary">로그인</button>
  </form>
  <p class="muted">계정 생성 및 비밀번호 초기화는 관리자에게 요청하세요.</p>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
