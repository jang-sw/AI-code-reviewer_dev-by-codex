<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="card">
  <p class="eyebrow">HTTP <c:out value="${statusCode}"/></p>
  <h1><c:out value="${errorTitle}"/></h1>
  <p class="muted"><c:out value="${errorMessage}" default="일시적인 문제로 요청을 완료하지 못했습니다. 잠시 후 다시 확인하고, 문제가 계속되면 관리자에게 문의해 주세요."/></p>
  <c:url var="errorRecoveryUrl" value="${empty recoveryPath ? '/' : recoveryPath}"/>
  <div class="actions"><a class="button" href="<c:out value='${errorRecoveryUrl}'/>"><c:out value="${recoveryLabel}" default="대시보드로 돌아가기"/></a>
    <c:if test="${recoveryPath != '/login'}"><c:url var="errorLoginUrl" value="/login"/><a href="<c:out value='${errorLoginUrl}'/>">로그인 화면 열기</a></c:if>
  </div>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
