<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="card" aria-labelledby="recovery-error-title">
  <h1 id="recovery-error-title">리뷰 진행 기준을 복구하지 못했습니다</h1>
  <p class="notice error" role="alert"><c:out value="${recoveryErrorMessage}"/></p>
  <p>프로젝트로 돌아가 상태와 입력 내용을 확인해 주세요. 복구 요청은 적용되지 않았습니다.</p>
  <c:url var="recoveryProjectUrl" value="/projects/${projectId}"/>
  <a class="button button-secondary" href="<c:out value='${recoveryProjectUrl}'/>">프로젝트로 돌아가기</a>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
