<%@ page pageEncoding="UTF-8" %>
<%@ include file="fragments/header.jspf" %>
<section class="page-heading">
  <p class="eyebrow">나의 작업 공간</p>
  <h1>오늘 확인할 코드 리뷰</h1>
  <p>프로젝트의 새 변경과 나에게 배정된 수정 권고를 한곳에서 확인하세요.</p>
  <div class="actions"><a class="button" href="<c:url value='/issues'/>">내 미처리 이슈 확인</a><a class="button button-secondary" href="<c:url value='/projects#register'/>">프로젝트 등록</a></div>
</section>
<section class="stats-grid" aria-label="리뷰 통계">
  <article class="stat-card"><span>등록 프로젝트</span><strong><c:out value="${stats.projectCount}"/></strong></article>
  <article class="stat-card"><a href="<c:url value='/projects?status=PENDING'/>">승인 대기 프로젝트</a><strong><c:out value="${stats.pendingCount}"/></strong></article>
  <article class="stat-card"><a href="<c:url value='/issues'/>">미처리 이슈</a><strong><c:out value="${stats.openIssueCount}"/></strong></article>
  <article class="stat-card"><span>확인이 필요한 리뷰</span><strong><c:out value="${stats.failedRunCount}"/></strong></article>
</section>
<sec:authorize access="hasRole('ADMIN')"><aside class="notice admin-callout"><div><strong>관리자 승인함</strong><p>새 가입과 프로젝트 신청을 확인하고 승인해 주세요.</p></div><div class="actions"><a class="button button-secondary" href="<c:url value='/admin/users'/>">가입 신청 확인</a><a class="button button-secondary" href="<c:url value='/projects?status=PENDING'/>">프로젝트 신청 확인</a></div></aside></sec:authorize>
<section class="panel">
  <div class="section-heading"><h2>최근 프로젝트</h2><a href="<c:url value='/projects'/>">전체 프로젝트 보기</a></div>
  <c:choose>
    <c:when test="${empty projects}"><div class="empty-state"><h3>등록된 프로젝트가 없습니다</h3><p>GitHub 또는 GitLab 저장소 주소를 등록하고 관리자 승인을 받아 시작하세요.</p><a class="button" href="<c:url value='/projects'/>">프로젝트 등록하기</a></div></c:when>
    <c:otherwise>
      <div class="table-wrap"><table><thead><tr><th>프로젝트</th><th>승인 상태</th><th>최근 리뷰</th><th>기록</th></tr></thead><tbody>
      <c:forEach items="${projects}" var="project"><tr>
        <td><c:url var="projectUrl" value="/projects/${project.id}"/><a href="<c:out value='${projectUrl}'/>"><c:out value="${project.name}"/></a></td>
        <td><ui:status value="${project.status}"/></td>
        <td><ui:status value="${project.review_status}"/></td>
        <td><c:url var="reviewUrl" value="/reviews"><c:param name="projectId" value="${project.id}"/></c:url><a href="<c:out value='${reviewUrl}'/>">리뷰 기록</a></td>
      </tr></c:forEach>
      </tbody></table></div>
    </c:otherwise>
  </c:choose>
</section>
<aside class="panel"><h2>이렇게 시작하세요</h2><ol class="journey-steps"><li><strong>저장소 등록</strong><span>Git 주소를 붙여넣어 프로젝트를 신청합니다.</span></li><li><strong>관리자 승인</strong><span><c:choose><c:when test="${scheduledReviewEnabled}">승인 후 예약 일정에 따라 리뷰를 시작합니다.</c:when><c:otherwise>승인 후 프로젝트에서 ‘지금 리뷰하기’를 누르세요. 현재 자동 리뷰는 꺼져 있습니다.</c:otherwise></c:choose></span></li><li><strong>이슈 확인</strong><span>나에게 배정된 권고의 근거를 읽고 처리합니다.</span></li></ol><p class="muted">처음에는 과거 커밋부터 순서대로 검토하므로 이력에 따라 시간이 걸릴 수 있습니다.</p></aside>
<%@ include file="fragments/footer.jspf" %>
