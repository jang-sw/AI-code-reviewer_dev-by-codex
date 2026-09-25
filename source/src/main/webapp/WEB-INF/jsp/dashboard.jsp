<%@ page pageEncoding="UTF-8" %>
<%@ include file="fragments/header.jspf" %>
<section class="page-heading">
  <p class="eyebrow">REVIEW OVERVIEW</p>
  <h1>코드 리뷰 현황</h1>
  <p>승인된 프로젝트의 커밋을 매시간 확인하고 수정 권고를 담당자의 이슈함에 전달합니다.</p>
</section>
<section class="stats-grid" aria-label="리뷰 통계">
  <article class="stat-card"><span>등록 프로젝트</span><strong><c:out value="${stats.projectCount}"/></strong></article>
  <article class="stat-card"><span>승인 대기</span><strong><c:out value="${stats.pendingCount}"/></strong></article>
  <article class="stat-card"><span>열린 이슈</span><strong><c:out value="${stats.openIssueCount}"/></strong></article>
  <article class="stat-card"><span>최근 리뷰 실패</span><strong><c:out value="${stats.failedRunCount}"/></strong></article>
</section>
<section class="panel">
  <div class="section-heading"><h2>최근 프로젝트</h2><a href="<c:url value='/projects'/>">전체 프로젝트 보기</a></div>
  <c:choose>
    <c:when test="${empty projects}"><div class="empty-state"><h3>등록된 프로젝트가 없습니다</h3><p>GitHub 또는 GitLab 저장소 주소를 등록하고 관리자 승인을 받아 시작하세요.</p><a class="button" href="<c:url value='/projects'/>">프로젝트 등록하기</a></div></c:when>
    <c:otherwise>
      <div class="table-wrap"><table><thead><tr><th>프로젝트</th><th>승인 상태</th><th>최근 리뷰</th><th>기록</th></tr></thead><tbody>
      <c:forEach items="${projects}" var="project"><tr>
        <td><c:url var="projectUrl" value="/projects/${project.id}"/><a href="<c:out value='${projectUrl}'/>"><c:out value="${project.name}"/></a></td>
        <td><span class="badge"><c:out value="${project.status}"/></span></td>
        <td><c:choose><c:when test="${empty project.review_status}">미실행</c:when><c:otherwise><c:out value="${project.review_status}"/></c:otherwise></c:choose></td>
        <td><c:url var="reviewUrl" value="/reviews"><c:param name="projectId" value="${project.id}"/></c:url><a href="<c:out value='${reviewUrl}'/>">리뷰 기록</a></td>
      </tr></c:forEach>
      </tbody></table></div>
    </c:otherwise>
  </c:choose>
</section>
<aside class="panel muted"><h2>리뷰와 이슈 배정</h2><p>최초 승인 후 전체 커밋을 오래된 순서로 나누어 리뷰합니다. 이후 완료한 배치부터 이어서 진행합니다. GitHub 계정 연결 또는 관리자가 등록한 저장소별 작성자 이메일 매핑으로 활성 사용자에게 이슈를 배정하며, 일치하는 계정이 없으면 프로젝트 소유자에게 배정합니다.</p><a href="<c:url value='/issues'/>">내 이슈함 열기</a></aside>
<%@ include file="fragments/footer.jspf" %>
