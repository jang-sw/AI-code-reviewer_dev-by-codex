<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">PROJECT DETAIL</p><h1><c:out value="${project.name}"/></h1><p><c:out value="${project.repositoryUrl}"/></p></section>
<section class="card">
  <h2>리뷰 설정</h2>
  <dl class="detail-grid"><dt>소유자</dt><dd><c:out value="${project.ownerUsername}"/></dd>
    <dt>저장소 제공자</dt><dd><c:out value="${project.provider}"/></dd>
    <dt>상태</dt><dd><span class="badge status-${project.status}"><c:choose><c:when test="${project.status == 'PENDING'}">관리자 승인 대기</c:when><c:when test="${project.status == 'APPROVED'}">리뷰 활성</c:when><c:when test="${project.status == 'PAUSED'}">일시 중지</c:when><c:otherwise>반려</c:otherwise></c:choose></span></dd>
    <dt>브랜치</dt><dd><c:out value="${project.reviewBranch}" default="저장소 기본 브랜치"/></dd>
    <dt>리뷰 주기</dt><dd>매시간 · 최초 전체 커밋 이력부터 순차 처리</dd>
    <dt>마지막 성공 커밋</dt><dd><c:out value="${project.lastReviewedSha}" default="아직 없음"/></dd>
    <dt>등록 시각 (UTC)</dt><dd><c:out value="${project.createdAt}"/></dd>
  </dl>
  <p class="muted">Git 작성자 계정이 등록된 사용자와 일치하면 해당 사용자에게 이슈를 배정합니다. 일치하지 않으면 프로젝트 소유자에게 배정합니다.</p>
  <div class="actions">
    <a class="button secondary" href="${pageContext.request.contextPath}/reviews?projectId=${project.id}">리뷰 실행 기록</a>
    <c:if test="${project.approved}"><form method="post" action="${pageContext.request.contextPath}/projects/${project.id}/review"><input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"><button type="submit" class="button primary">지금 리뷰 실행</button></form></c:if>
    <a href="${pageContext.request.contextPath}/projects">프로젝트 목록</a>
  </div>
</section>
<c:if test="${isAdmin}"><section class="card"><h2>관리자 승인</h2><p>승인하면 예약 리뷰가 활성화됩니다. 저장소의 전체 이력은 오래된 커밋부터 여러 실행에 걸쳐 처리됩니다.</p>
  <div class="actions">
    <c:if test="${not project.approved}"><form method="post" action="${pageContext.request.contextPath}/admin/projects/${project.id}/approve"><input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"><button type="submit" class="button primary"><c:choose><c:when test="${project.status == 'PAUSED'}">리뷰 재개</c:when><c:otherwise>승인</c:otherwise></c:choose></button></form></c:if>
    <c:if test="${project.status == 'PENDING'}"><form method="post" action="${pageContext.request.contextPath}/admin/projects/${project.id}/reject"><input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"><button type="submit" class="button secondary">반려</button></form></c:if>
    <c:if test="${project.approved}"><form method="post" action="${pageContext.request.contextPath}/admin/projects/${project.id}/pause"><input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"><button type="submit" class="button secondary">리뷰 일시 중지</button></form></c:if>
  </div>
</section></c:if>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
