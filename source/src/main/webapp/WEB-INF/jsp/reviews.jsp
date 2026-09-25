<%@ page pageEncoding="UTF-8" %>
<%@ include file="fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">REVIEW HISTORY</p><h1><c:out value="${projectName}"/> 리뷰 기록</h1><p>전체 이력을 오래된 순서로 처리합니다. 한 배치가 끝나면 다음 예약 실행에서 이어서 진행합니다.</p></section>
<c:if test="${queued}"><div class="notice" role="status">리뷰가 대기 중이거나 실행 중입니다. 결과를 확인하려면 이 화면을 새로고침하세요.</div></c:if>
<div class="toolbar">
  <c:url var="projectUrl" value="/projects/${projectId}"/><a href="<c:out value='${projectUrl}'/>">프로젝트로 돌아가기</a>
  <c:if test="${projectStatus == 'APPROVED'}"><c:url var="reviewAction" value="/projects/${projectId}/review"/><form method="post" action="<c:out value='${reviewAction}'/>"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><button type="submit">지금 리뷰 실행</button></form></c:if>
</div>
<section class="panel"><h2>마지막 완료 배치의 기준 커밋</h2><c:choose><c:when test="${empty cursor}"><p>아직 끝까지 완료한 리뷰 배치가 없습니다. 실패한 배치에서 저장된 커밋은 재시도 시 중복 리뷰하지 않습니다.</p></c:when><c:otherwise><code class="commit-sha"><c:out value="${cursor}"/></code></c:otherwise></c:choose></section>
<section class="panel"><h2>최근 실행 50건</h2>
  <c:choose><c:when test="${empty runs}"><p class="empty-state">리뷰 실행 기록이 없습니다. 승인 후 예약 실행 또는 지금 리뷰 실행으로 시작하세요.</p></c:when><c:otherwise>
  <div class="table-wrap"><table><thead><tr><th>실행</th><th>상태</th><th>성공 커밋 수</th><th>시작</th><th>종료 / 오류</th></tr></thead><tbody>
    <c:forEach items="${runs}" var="run"><tr><td>#<c:out value="${run.id}"/></td><td><span class="badge"><c:out value="${run.status}"/></span></td><td><c:out value="${run.reviewed_commits}"/></td><td><c:out value="${run.started_at}"/></td><td><c:out value="${run.finished_at}"/><c:if test="${not empty run.error_message}"><p class="error-text"><c:out value="${run.error_message}"/></p></c:if></td></tr></c:forEach>
  </tbody></table></div></c:otherwise></c:choose>
</section>
<section class="panel"><h2>최근 리뷰 커밋 50건</h2>
  <c:choose><c:when test="${empty commits}"><p class="empty-state">완료된 커밋 리뷰가 없습니다.</p></c:when><c:otherwise>
    <c:forEach items="${commits}" var="commit"><article class="review-card">
      <div class="section-heading"><code class="commit-sha"><c:out value="${commit.commit_sha}"/></code><span>이슈 <c:out value="${commit.issue_count}"/>건</span></div>
      <p><span class="badge"><c:choose><c:when test="${commit.coverage_type == 'EMPTY'}">파일 변경 없음 · AI 본문 검토 없음</c:when><c:when test="${commit.coverage_type == 'METADATA_ONLY'}">메타데이터 변경 · 수동 확인 필요</c:when><c:otherwise>AI 본문 리뷰<c:if test="${not empty commit.coverage_details}"> · 메타데이터 변경 포함</c:if></c:otherwise></c:choose></span></p>
      <p class="muted">작성자: <c:out value="${commit.author_login}" default="Git 계정 미확인"/> · <c:out value="${commit.reviewed_at}"/></p>
      <p class="preserve-lines"><c:out value="${commit.summary}"/></p>
      <c:if test="${commit.coverage_type == 'METADATA_ONLY'}"><p class="notice">경로·권한·파일 유형 변경을 수동 확인해 주세요. 심볼릭 링크로의 변경도 포함될 수 있습니다. 이 커밋은 별도 수정 권고 이슈를 자동 생성하지 않습니다.</p></c:if>
      <c:if test="${not empty commit.coverage_details}"><details><summary>검토 범위와 메타데이터 변경</summary><pre><c:out value="${commit.coverage_details}"/></pre></details></c:if>
    </article></c:forEach>
  </c:otherwise></c:choose>
</section>
<p class="muted">AI 리뷰는 보조 의견입니다. 변경 전 코드 맥락과 테스트 결과를 확인하세요. 실패한 커밋은 성공으로 기록하거나 진행 위치를 넘기지 않습니다.</p>
<%@ include file="fragments/footer.jspf" %>
