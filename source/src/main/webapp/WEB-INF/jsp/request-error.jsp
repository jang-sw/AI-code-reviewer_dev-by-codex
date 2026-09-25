<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="card">
  <p class="eyebrow">HTTP <c:out value="${statusCode}"/></p>
  <h1><c:out value="${errorTitle}"/></h1>
  <p class="muted">로그인 상태와 접근 권한을 확인한 뒤 다시 시도해 주세요. 문제가 계속되면 관리자에게 문의해 주세요.</p>
  <a class="button" href="<c:url value='/'/>">대시보드로 돌아가기</a>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
