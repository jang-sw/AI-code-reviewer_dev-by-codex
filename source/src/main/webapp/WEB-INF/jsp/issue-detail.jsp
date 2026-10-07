<%@ page pageEncoding="UTF-8" %>
<%@ include file="fragments/header.jspf" %>
<c:url var="listUrl" value="/issues"><c:param name="status" value="${filterStatus}"/><c:param name="page" value="${page}"/></c:url>
<p><a href="<c:out value='${listUrl}'/>">← 이슈 목록으로</a></p>
<c:if test="${not empty reasonError}"><div class="notice error"><strong>처리 상태와 사유를 저장하지 않았습니다.</strong> <a href="#issue-reason-<c:out value='${issue.id}'/>">수동 확인 사유를 수정해 주세요.</a></div></c:if>
<section class="page-heading"><p class="eyebrow"><c:out value="${issue.project_name}"/> · 이슈 #<c:out value="${issue.id}"/></p><h1><c:out value="${issue.title}"/></h1><p><c:choose><c:when test="${issue.issue_kind == 'MANUAL_REVIEW'}"><span class="badge badge-warning">수동 확인</span></c:when><c:otherwise><ui:status value="${issue.severity}"/></c:otherwise></c:choose> <c:choose><c:when test="${issue.issue_kind == 'MANUAL_REVIEW' and issue.status == 'RESOLVED'}"><span class="badge badge-success">확인 완료</span></c:when><c:otherwise><ui:status value="${issue.status}"/></c:otherwise></c:choose></p></section>
<section class="panel"><h2>변경 위치와 담당자</h2><dl>
  <dt>담당자</dt><dd><c:out value="${issue.assignee_username}"/></dd>
  <dt>파일 위치</dt><dd><code class="file-path"><c:out value="${issue.file_path}"/><c:if test="${not empty issue.line_number}">:<c:out value="${issue.line_number}"/></c:if></code></dd>
  <dt>Git 작성자</dt><dd><c:out value="${issue.author_login}" default="미확인"/></dd>
  <dt>배정 근거</dt><dd><c:choose><c:when test="${issue.assignment_reason == 'GITHUB_ACCOUNT'}">GitHub 계정 연결</c:when><c:when test="${issue.assignment_reason == 'GIT_EMAIL_MAPPING'}">관리자가 연결한 Git 작성자 정보</c:when><c:when test="${issue.assignment_reason == 'PROJECT_OWNER_FALLBACK'}">프로젝트 소유자 대체 배정</c:when><c:otherwise>기존 이슈 (배정 근거 미기록)</c:otherwise></c:choose></dd>
  <dt>커밋</dt><dd><code class="commit-sha"><c:out value="${issue.commit_sha}"/></code><c:if test="${not empty issue.commit_url}"> · <a href="<c:out value='${issue.commit_url}'/>" target="_blank" rel="noopener noreferrer">원본 커밋 보기</a></c:if></dd>
</dl><c:if test="${issue.assignment_reason == 'PROJECT_OWNER_FALLBACK' or issue.fallback_assignment}"><p class="notice assignment-notice">Git 작성자와 일치하는 활성 계정이나 작성자 매핑이 없어 프로젝트 소유자에게 배정했습니다. 연결이 필요하면 관리자에게 문의하세요.</p></c:if></section>
<c:choose><c:when test="${issue.issue_kind == 'MANUAL_REVIEW'}">
<section class="panel"><h2>직접 확인이 필요한 파일</h2><p class="notice">AI 본문 검토 없이 배정된 파일별 수동 확인 업무입니다. 아래 사유와 변경 영향을 직접 확인해 주세요.</p><p><strong>확인 사유:</strong> <c:out value="${issue.manual_reason_label}"/></p><p class="preserve-lines"><c:out value="${issue.description}"/></p><h2>확인 방법</h2><p class="preserve-lines"><c:out value="${issue.suggestion}"/></p>
  <h2>파일 변경 확인 근거</h2><p>변경 전·후의 고정된 Git 파일 목록을 대조한 기록입니다. 파일 본문의 AI 검토 완료를 뜻하지 않습니다.</p><dl>
    <dt>이전 파일 객체</dt><dd><code><c:out value="${issue.manual_old_object_sha}" default="없음"/></code></dd>
    <dt>변경 후 파일 객체</dt><dd><code><c:out value="${issue.manual_new_object_sha}" default="없음"/></code></dd>
    <dt>이전 파일 모드</dt><dd><c:out value="${issue.manual_old_mode}" default="없음"/></dd>
    <dt>변경 후 파일 모드</dt><dd><c:out value="${issue.manual_new_mode}" default="없음"/></dd>
  </dl><p class="hint">파일 객체가 한쪽에만 있으면 추가 또는 삭제된 파일입니다. 원본 커밋에서 이 파일의 내용과 사용 위치를 직접 확인해 주세요.</p>
</section>
</c:when><c:otherwise>
<section class="panel"><h2>검토 내용</h2><p class="preserve-lines"><c:out value="${issue.description}"/></p><h2>수정 권고</h2><pre class="suggestion"><c:out value="${issue.suggestion}"/></pre><p class="hint">AI의 권고입니다. 실제 코드의 맥락과 테스트 결과를 확인한 뒤 반영하세요.</p></section>
</c:otherwise></c:choose>
<section class="panel"><h2>처리 결과 기록</h2><c:choose><c:when test="${issue.issue_kind == 'MANUAL_REVIEW'}"><p>직접 확인하고 필요한 조치를 마쳤다면 ‘확인 완료’, 확인 후 적용 대상이 아니라고 판단했다면 ‘검토 제외’를 선택하고 확인한 내용과 이유를 남기세요. 다시 ‘미처리’로 바꿀 때에도 이유가 필요합니다. 상태를 바꿔도 수동 확인 업무였다는 기록은 보존됩니다.</p><c:if test="${not empty issue.resolution_note}"><h3>마지막 처리 기록</h3><p class="preserve-lines"><c:out value="${issue.resolution_note}"/></p></c:if></c:when><c:otherwise><p>수정을 반영했다면 ‘해결’, 검토 결과 적용하지 않기로 했다면 ‘검토 제외’를 선택하세요. 다시 확인할 때는 ‘미처리’로 바꿀 수 있습니다.</p></c:otherwise></c:choose><%@ include file="fragments/issue-status.jspf" %></section>
<%@ include file="fragments/footer.jspf" %>
