package org.esupportail.esupsignature.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.europa.esig.dss.validation.reports.Reports;
import org.apache.commons.io.IOUtils;
import org.esupportail.esupsignature.config.GlobalProperties;
import org.esupportail.esupsignature.dss.service.FOPService;
import org.esupportail.esupsignature.entity.*;
import org.esupportail.esupsignature.entity.enums.*;
import org.esupportail.esupsignature.exception.*;
import org.esupportail.esupsignature.repository.DataRepository;
import org.esupportail.esupsignature.repository.SignBookRepository;
import org.esupportail.esupsignature.repository.WorkflowRepository;
import org.esupportail.esupsignature.service.interfaces.fs.FsAccessFactoryService;
import org.esupportail.esupsignature.service.interfaces.fs.FsAccessService;
import org.esupportail.esupsignature.service.interfaces.fs.FsFile;
import org.esupportail.esupsignature.service.interfaces.prefill.PreFillService;
import org.esupportail.esupsignature.service.mail.MailService;
import org.esupportail.esupsignature.service.security.otp.OtpService;
import org.esupportail.esupsignature.service.utils.WebUtilsService;
import org.esupportail.esupsignature.service.utils.file.FileService;
import org.esupportail.esupsignature.service.utils.metric.CustomMetricsService;
import org.esupportail.esupsignature.service.utils.pdf.PdfService;
import org.esupportail.esupsignature.service.utils.sign.SignService;
import org.esupportail.esupsignature.web.ws.json.JsonExternalUserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@EnableConfigurationProperties(GlobalProperties.class)
public class SignBookService {

    private static final Logger logger = LoggerFactory.getLogger(SignBookService.class);

    private final GlobalProperties globalProperties;

    @Resource
    private MessageSource messageSource;

    @Resource
    private SignBookRepository signBookRepository;

    @Resource
    private SignRequestService signRequestService;

    @Resource
    private UserService userService;

    @Resource
    private FsAccessFactoryService fsAccessFactoryService;

    @Resource
    private WebUtilsService webUtilsService;

    @Resource
    private FileService fileService;

    @Resource
    private PdfService pdfService;

    @Resource
    private WorkflowService workflowService;

    @Resource
    private MailService mailService;

    @Resource
    private WorkflowStepService workflowStepService;

    @Resource
    private LiveWorkflowService liveWorkflowService;

    @Resource
    private LiveWorkflowStepService liveWorkflowStepService;

    @Resource
    private DataService dataService;

    @Resource
    private LogService logService;

    @Resource
    private TargetService targetService;

    @Resource
    private UserPropertieService userPropertieService;

    @Resource
    private CommentService commentService;

    @Resource
    private OtpService otpService;

    @Resource
    private DataRepository dataRepository;

    @Resource
    private WorkflowRepository workflowRepository;

    @Resource
    private UserShareService userShareService;

    @Resource
    private SignService signService;

    @Resource
    private RecipientService recipientService;

    @Resource
    private ValidationService validationService;

    @Resource
    private DocumentService documentService;

    @Resource
    private SignRequestParamsService signRequestParamsService;

    @Resource
    private CustomMetricsService customMetricsService;

    @Resource
    private PreFillService preFillService;

    @Resource
    private ReportService reportService;

    @Resource
    private FOPService fopService;

    @Resource
    private CertificatService certificatService;

    public SignBookService(GlobalProperties globalProperties) {
        this.globalProperties = globalProperties;
    }

    public List<SignBook> getAllSignBooks() {
        List<SignBook> list = new ArrayList<>();
        signBookRepository.findAll().forEach(list::add);
        return list;
    }

    @Transactional
    public int countSignBooksByWorkflow(Long workflowId) {
        Workflow workflow = workflowRepository.findById(workflowId).get();
        return signBookRepository.countByLiveWorkflowWorkflow(workflow);
    }

    @Transactional
    public Page<SignBook> getSignBooks(String userEppn, String statusFilter, String recipientsFilter, String workflowFilter, String docTitleFilter, Pageable pageable) {
        Page<SignBook> signBooks = new PageImpl<>(new ArrayList<>());
        if(statusFilter.isEmpty() || statusFilter.equals("all")) {
            if(!workflowFilter.equals("Hors circuit")) {
                if(recipientsFilter != null && !recipientsFilter.equals("%") && !recipientsFilter.isEmpty()) {
                    signBooks = signBookRepository.findByRecipientAndCreateByEppn(recipientsFilter, userEppn, workflowFilter, docTitleFilter, pageable);
                } else {
                    signBooks = signBookRepository.findByRecipientAndCreateByEppn(userEppn, workflowFilter, docTitleFilter, pageable);
                }
            } else {
                signBooks = signBookRepository.findByRecipientAndCreateByEppnAndTitleNull(recipientsFilter, userEppn, pageable);
            }
        } else if(statusFilter.equals("tosign"))  {
            signBooks = signBookRepository.findToSign(userEppn, pageable);
        } else if(statusFilter.equals("signedByMe")) {
            signBooks = signBookRepository.findByRecipientAndActionType(userEppn, ActionType.signed, pageable);
        } else if(statusFilter.equals("refusedByMe")) {
            signBooks = signBookRepository.findByRecipientAndActionType(userEppn, ActionType.refused, pageable);
        } else if(statusFilter.equals("followByMe")) {
            signBooks = signBookRepository.findByViewersContaining(userEppn, pageable);
        } else if(statusFilter.equals("sharedSign")) {
//            signBooks = signBookRepository.findByViewersContaining(userService.getUserByEppn(userEppn), pageable);
            //TODO
        } else if(statusFilter.equals("hided")) {
            signBooks = signBookRepository.findByHidedByEppn(userEppn, pageable);
        } else if(statusFilter.equals("empty")) {
            signBooks = signBookRepository.findEmpty(userEppn, pageable);
        } else {
            signBooks = signBookRepository.findByCreateByEppnAndStatusAndSignRequestsNotNull(userEppn, SignRequestStatus.valueOf(statusFilter), pageable);
        }

        for(SignBook signBook : signBooks) {
            if(signBook.getEndDate() == null &&
                    (signBook.getStatus().equals(SignRequestStatus.completed)
                    || signBook.getStatus().equals(SignRequestStatus.exported)
                    || signBook.getStatus().equals(SignRequestStatus.refused)
                    || signBook.getStatus().equals(SignRequestStatus.signed)
                    || signBook.getStatus().equals(SignRequestStatus.archived)
                    || signBook.getStatus().equals(SignRequestStatus.deleted))) {
                List<Action> actions = signBook.getSignRequests().stream().map(SignRequest::getRecipientHasSigned).map(Map::values).flatMap(Collection::stream).filter(action -> action.getDate() != null).sorted(Comparator.comparing(Action::getDate).reversed()).collect(Collectors.toList());
                if(actions.size() > 0) {
                    signBook.setEndDate(actions.get(0).getDate());
                }
            }
        }
        return signBooks;
    }

//    @Transactional
//    public List<SignBook> getSignBooks(String userEppn, String authUserEppn, String statusFilter, String recipientsFilter, String workflowFilter, String docTitleFilter) {
//        Pageable pageable = Pageable.unpaged();
//        Page<SignBook> signBooks = new PageImpl<>(new ArrayList<>());
//        if(statusFilter.isEmpty() || statusFilter.equals("all")) {
//            if(!workflowFilter.equals("Hors circuit")) {
//                if(recipientsFilter != null && !recipientsFilter.isEmpty()) {
//                    signBooks = signBookRepository.findByRecipientAndCreateByEppn(recipientsFilter, userEppn, workflowFilter, docTitleFilter, pageable);
//                } else {
//                    signBooks = signBookRepository.findByRecipientAndCreateByEppn(userEppn, workflowFilter, docTitleFilter, pageable);
//                }
//            } else {
//                signBooks = signBookRepository.findByRecipientAndCreateByEppnAndTitleNull(recipientsFilter, userEppn, pageable);
//            }
//        } else if(statusFilter.equals("tosign"))  {
//            signBooks = signBookRepository.findToSign(userEppn, pageable);
//        } else if(statusFilter.equals("signedByMe")) {
//            signBooks = signBookRepository.findByRecipientAndActionType(userEppn, ActionType.signed, pageable);
//        } else if(statusFilter.equals("refusedByMe")) {
//            signBooks = signBookRepository.findByRecipientAndActionType(userEppn, ActionType.refused, pageable);
//        } else if(statusFilter.equals("followByMe")) {
//            signBooks = signBookRepository.findByViewersContaining(userEppn, pageable);
//        } else if(statusFilter.equals("sharedSign")) {
////            signBooks = signBookRepository.findByViewersContaining(userService.getUserByEppn(userEppn), pageable);
//            //TODO
//        } else if(statusFilter.equals("hided")) {
//            signBooks = signBookRepository.findByHidedByEppn(userEppn, pageable);
//        } else {
//            signBooks = signBookRepository.findByCreateByEppnAndStatusAndSignRequestsNotNull(userEppn, SignRequestStatus.valueOf(statusFilter), pageable);
//        }
//
//        for(SignBook signBook : signBooks) {
//            for (SignRequest signRequest : signBook.getSignRequests()) {
//                if (signRequest.getEndDate() == null) {
//                    signRequest.setEndDate(getEndDate(signRequest));
//                }
//            }
//        }
//        return signBooks.getContent();
//    }


    @Transactional
    public SignBook addFastSignRequestInNewSignBook(MultipartFile[] multipartFiles, SignType signType, User user, String authUserEppn) throws EsupSignatureException {
        if (signService.checkSignTypeDocType(signType, multipartFiles[0])) {
            try {
                String name = fileService.getNameOnly(multipartFiles[0].getOriginalFilename());
                SignBook signBook = addDocsInNewSignBookSeparated(name, name, "Auto signature", multipartFiles, user);
                signBook.getLiveWorkflow().getLiveWorkflowSteps().add(liveWorkflowStepService.createLiveWorkflowStep(null,false, null,true, false, false, signType, Collections.singletonList(user.getEmail()), null));
                signBook.getLiveWorkflow().setCurrentStep(signBook.getLiveWorkflow().getLiveWorkflowSteps().get(0));
                workflowService.dispatchSignRequestParams(signBook);
                pendingSignBook(signBook.getId(), null, user.getEppn(), authUserEppn, false);
                return signBook;
            } catch (EsupSignatureIOException e) {
                throw new EsupSignaturePdfException("Impossible de charger le document suite à une erreur interne", e);
            }
        } else {
            throw new EsupSignatureException("Impossible de demander une signature visuelle sur un document du type " + multipartFiles[0].getContentType());
        }
    }

    public List<User> getRecipientsNames(String userEppn) {
        return signBookRepository.findRecipientNames(userEppn);
    }

    public SignBook createSignBook(String title, String name, Workflow workflow, String prefix, String namingTemplate, User user, boolean external) {
        SignBook signBook = new SignBook();
        if(namingTemplate == null || namingTemplate.isEmpty()) {
            namingTemplate = globalProperties.getNamingTemplate();
            if(workflow != null && workflow.getNamingTemplate() != null && !workflow.getNamingTemplate().isEmpty()) {
                namingTemplate = workflow.getNamingTemplate();
            }
        }
        String workflowName = prefix;
        int order = 0;
        if(workflow != null) {
            workflowName = workflow.getName();
            order = signBookRepository.countByLiveWorkflowWorkflow(workflow);
        }
        signBook.setStatus(SignRequestStatus.draft);
        signBook.setTitle(title);
        signBook.setCreateBy(user);
        signBook.setCreateDate(new Date());
        signBook.setExternal(external);
        signBook.setLiveWorkflow(liveWorkflowService.create(prefix));
        signBookRepository.save(signBook);
        signBook.setName(signRequestService.generateName(signBook, name, workflowName, order, user, namingTemplate));
        return signBook;
    }

    @Transactional
    public void initSignBook(Long signBookId, Long id, User user) {
        SignBook signBook = getById(signBookId);
        Workflow workflow = workflowRepository.findById(id).get();
        signBook.setName(workflow.getName() + "_" + new Date() + "_" + user.getEppn());
        signBook.setTitle(workflow.getDescription());
        signBook.getLiveWorkflow().setWorkflow(workflow);
        for(Target target : workflow.getTargets()) {
            signBook.getLiveWorkflow().getTargets().add(targetService.createTarget(target.getTargetUri()));
        }
    }

    public SignBook getById(Long id) {
        Optional<SignBook> signBook = signBookRepository.findById(id);
        if(signBook.isPresent()) {
            signBook.get().setLogs(getLogsFromSignBook(signBook.get()));
            return signBook.get();
        }
        return null;
    }

    public List<SignBook> getByWorkflowId(Long id) {
        return signBookRepository.findByWorkflowId(id);
    }

    @Transactional
    public boolean delete(Long signBookId, String userEppn) {
        SignBook signBook = getById(signBookId);
        if(signBook.getStatus().equals(SignRequestStatus.deleted)) {
            deleteDefinitive(signBookId);
            return true;
        }
        List<Long> signRequestsIds = signBook.getSignRequests().stream().map(SignRequest::getId).collect(Collectors.toList());
        for(Long signRequestId : signRequestsIds) {
            signRequestService.deleteSignRequest(signRequestId, userEppn);
        }
        for(LiveWorkflowStep liveWorkflowStep : signBook.getLiveWorkflow().getLiveWorkflowSteps()) {
            if(liveWorkflowStep.getSignRequestParams() != null) {
                liveWorkflowStep.getSignRequestParams().clear();
            }
        }
        signBook.setStatus(SignRequestStatus.deleted);
        signBook.setUpdateDate(new Date());
        signBook.setUpdateBy(userEppn);
        logger.info("delete signbook : " + signBookId);
        return false;
    }

    public void nullifySignBook(SignBook signBook) {
        Data data = getBySignBook(signBook);
        if(data != null) data.setSignBook(null);
    }

    @Transactional
    public void deleteDefinitive(Long signBookId) {
        SignBook signBook = getById(signBookId);
        signBook.getLiveWorkflow().setCurrentStep(null);
        List<Long> liveWorkflowStepIds = signBook.getLiveWorkflow().getLiveWorkflowSteps().stream().map(LiveWorkflowStep::getId).collect(Collectors.toList());
        signBook.getLiveWorkflow().getLiveWorkflowSteps().clear();
        for (Long liveWorkflowStepId : liveWorkflowStepIds) {
            liveWorkflowStepService.delete(liveWorkflowStepId);
        }
        List<Long> signRequestsIds = signBook.getSignRequests().stream().map(SignRequest::getId).collect(Collectors.toList());
        for(Long signRequestId : signRequestsIds) {
            signRequestService.deleteDefinitive(signRequestId);
        }
        dataService.deleteBySignBook(signBook);
        nullifySignBook(signBook);
        signBookRepository.delete(signBook);
        logger.info("definitive delete signbook : " + signBookId);
    }

    public boolean checkUserManageRights(String userEppn, SignBook signBook) {
        if(signBook.getSignRequests().size() == 1) {
            User user = userService.getUserByEppn(userEppn);
            Data data = getBySignBook(signBook);
            if(data != null && data.getForm() != null && !data.getForm().getManagers().isEmpty()) {
                if (data.getForm().getManagers().contains(user.getEmail())) {
                    return true;
                }
            }
        }
        return signBook.getCreateBy().getEppn().equals(userEppn);
    }

    @Transactional
    public boolean removeStep(Long signBookId, int step) {
        SignBook signBook = getById(signBookId);
        int currentStepNumber = signBook.getLiveWorkflow().getCurrentStepNumber();
        if(currentStepNumber <= step) {
            LiveWorkflowStep liveWorkflowStep = signBook.getLiveWorkflow().getLiveWorkflowSteps().get(step);
            signBook.getLiveWorkflow().getLiveWorkflowSteps().remove(liveWorkflowStep);
            for (Recipient recipient : liveWorkflowStep.getRecipients()) {
                for (SignRequest signRequest : signBook.getSignRequests()) {
                    signRequest.getRecipientHasSigned().remove(recipient);
                }
            }
            liveWorkflowStepService.delete(liveWorkflowStep);
            return true;
        }
        return false;
    }

    public void updateStatus(SignBook signBook, SignRequestStatus signRequestStatus, String action, String returnCode, String comment, String userEppn, String authUserEppn) {
        Log log = logService.create(signBook.getId(), signBook.getStatus().name(), action, returnCode, comment, userEppn, authUserEppn);
        if(signRequestStatus != null) {
            log.setFinalStatus(signRequestStatus.toString());
            signBook.setStatus(signRequestStatus);
        } else {
            log.setFinalStatus(signBook.getStatus().toString());
        }
    }

    public List<Log> getLogsFromSignBook(SignBook signBook) {
        List<Log> logs = new ArrayList<>();
        for (SignRequest signRequest : signBook.getSignRequests()) {
            logs.addAll(logService.getBySignRequestId(signRequest.getId()));
        }
        return logs;
    }

    public List<LiveWorkflowStep> getAllSteps(SignBook signBook) {
        List<LiveWorkflowStep> allSteps = new ArrayList<>(signBook.getLiveWorkflow().getLiveWorkflowSteps());
        if (allSteps.size() > 0) {
            allSteps.remove(0);
        }
        return allSteps;
    }

    @Transactional
    public void addLiveStep(Long id, List<String> recipientsEmails, int stepNumber, Boolean allSignToComplete, SignType signType, boolean repeatable, SignType repeatableSignType, boolean multiSign, boolean autoSign, String authUserEppn) throws EsupSignatureException {
        SignBook signBook = this.getById(id);
        int currentStepNumber = signBook.getLiveWorkflow().getCurrentStepNumber();
        LiveWorkflowStep liveWorkflowStep = liveWorkflowStepService.createLiveWorkflowStep(null, repeatable, repeatableSignType, multiSign, autoSign, allSignToComplete, signType, recipientsEmails, null);
        if (stepNumber == -1) {
            signBook.getLiveWorkflow().getLiveWorkflowSteps().add(liveWorkflowStep);
        } else {
            if (stepNumber >= currentStepNumber) {
                signBook.getLiveWorkflow().getLiveWorkflowSteps().add(stepNumber, liveWorkflowStep);
            } else {
                throw new EsupSignatureException("L'étape ne peut pas être ajoutée");
            }
        }
        if(recipientsEmails != null) {
            userPropertieService.createUserPropertieFromMails(userService.getByEppn(authUserEppn), recipientsEmails);
        }
    }

    @Transactional
    public void sendCCEmail(Long signBookId, List<String> recipientsCCEmails) throws EsupSignatureMailException {
        SignBook signBook = getById(signBookId);
        if(recipientsCCEmails != null) {
            addViewers(signBookId, recipientsCCEmails);
        }
        mailService.sendCCtAlert(signBook.getViewers().stream().map(User::getEmail).collect(Collectors.toList()), signBook.getSignRequests().get(0));
    }

    @Transactional
    public void addViewers(Long signBookId, List<String> recipientsCCEmails) {
        SignBook signBook = getById(signBookId);
        if(recipientsCCEmails != null) {
            for (String recipientsEmail : recipientsCCEmails) {
                User user = userService.getUserByEmail(recipientsEmail);
                if(!signBook.getViewers().contains(user)) {
                    signBook.getViewers().add(user);
                }
            }
        }
    }

    public Data getBySignRequest(SignRequest signRequest) {
        return getBySignBook(signRequest.getParentSignBook());
    }

    public Data getBySignBook(SignBook signBook) {
        return dataRepository.findBySignBook(signBook);
    }


    public Set<String> getDocTitles(String userEppn) {
        Set<String> docTitles = new HashSet<>();
        docTitles.addAll(signBookRepository.findDocTitles(userEppn));
        docTitles.addAll(signBookRepository.findDocNames(userEppn));
        docTitles.addAll(signBookRepository.findSignRequestTitles(userEppn));
        return docTitles;
    }

    public Collection<String> getWorkflowNames(String userEppn) {
        Set<String> docTitles = new HashSet<>();
        docTitles.addAll(signBookRepository.findLiveWorkflowTitles(userEppn));
        docTitles.addAll(signBookRepository.findWorkflowTitles(userEppn));
        docTitles.addAll(signBookRepository.findSignBookTitles(userEppn));
        return docTitles;
    }

    @Transactional
    public boolean toggle(Long id, String userEpppn) {
        SignBook signBook = getById(id);
        User user = userService.getUserByEppn(userEpppn);
        if(signBook.getHidedBy().contains(user)) {
            signBook.getHidedBy().remove(user);
            return false;
        } else {
            signBook.getHidedBy().add(user);
            return true;
        }
    }

    public int countEmpty(String userEppn) {
        return Math.toIntExact(signBookRepository.countEmpty(userEppn));
    }

    @Transactional
    public SignBook sendForSign(Long dataId, List<String> recipientsEmails, List<String> allSignToCompletes, List<JsonExternalUserInfo> externalUsersInfos, List<String> targetEmails, List<String> targetUrls, String userEppn, String authUserEppn, boolean forceSendEmail, Map<String, String> formDatas) throws EsupSignatureException, EsupSignatureIOException, EsupSignatureFsException {
        User user = userService.getUserByEppn(userEppn);
        User authUser = userService.getUserByEppn(authUserEppn);
        Data data = dataService.getById(dataId);
        if (recipientsEmails == null) {
            recipientsEmails = new ArrayList<>();
        }
        Form form = data.getForm();
        String name = form.getTitle().replaceAll("[\\\\/:*?\"<>|]", "-").replace("\t", "");
        Workflow modelWorkflow = data.getForm().getWorkflow();
        Workflow computedWorkflow = workflowService.computeWorkflow(modelWorkflow.getId(), recipientsEmails, allSignToCompletes, user.getEppn(), false);
        SignBook signBook = createSignBook(form.getTitle(), form.getTitle(), modelWorkflow, form.getTitle(),null, user, false);
        SignRequest signRequest = signRequestService.createSignRequest(null, signBook, user.getEppn(), authUser.getEppn());
        InputStream inputStream = dataService.generateFile(data);
        if(computedWorkflow.getWorkflowSteps().size() == 0) {
            try {
                inputStream = pdfService.convertGS(inputStream, signRequest.getToken());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        MultipartFile multipartFile = fileService.toMultipartFile(inputStream, name + ".pdf", "application/pdf");
        signRequestService.addDocsToSignRequest(signRequest, true, 0, form.getSignRequestParams(), multipartFile);
        workflowService.importWorkflow(signBook, computedWorkflow, externalUsersInfos);
        signRequestService.nextWorkFlowStep(signBook);
        Workflow workflow = workflowService.getById(form.getWorkflow().getId());
        targetService.copyTargets(workflow.getTargets(), signBook, targetEmails);
        if (targetUrls != null) {
            for (String targetUrl : targetUrls) {
                signBook.getLiveWorkflow().getTargets().add(targetService.createTarget(targetUrl));
            }
        }
        data.setSignBook(signBook);
        dataRepository.save(data);
        pendingSignBook(signBook.getId(), data, user.getEppn(), authUser.getEppn(), forceSendEmail);
        data.setStatus(SignRequestStatus.pending);
        for (String recipientEmail : recipientsEmails) {
            userPropertieService.createUserPropertieFromMails(userService.getByEppn(authUser.getEppn()), Collections.singletonList(recipientEmail.split("\\*")[1]));
        }
        if(workflow.getCounter() != null) {
            workflow.setCounter(workflow.getCounter() + 1);
        } else {
            workflow.setCounter(0);
        }
        if(formDatas != null && formDatas.size() > 0) {
//            Map<String, String> datas = formDatas.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> String.join(",", e.getValue())));
            dataService.updateDatas(form, data, formDatas, user, authUser);
        }
        return signBook;
    }

    public List<SignBook> getSharedSignBooks(String userEppn) {
        List<SignBook> sharedSignBook = new ArrayList<>();
        for(UserShare userShare : userShareService.getByToUsersInAndShareTypesContains(Collections.singletonList(userEppn), ShareType.sign)) {
            if(userShare.getWorkflow() != null) {
                sharedSignBook.addAll(getByWorkflowId(userShare.getWorkflow().getId()));
            } else if(userShare.getForm() != null) {
                List<SignRequest> signRequests = signRequestService.getToSignRequests(userShare.getUser().getEppn());
                for (SignRequest signRequest : signRequests) {
                    Data data = getBySignBook(signRequest.getParentSignBook());
                    if(data.getForm().equals(userShare.getForm())) {
                        sharedSignBook.add(signRequest.getParentSignBook());
                        break;
                    }
                }
            }
        }
        return sharedSignBook;
    }

    public void sendEmailAlertSummary(User recipientUser) throws EsupSignatureMailException {
        Date date = new Date();
        List<SignRequest> toSignSignRequests = signRequestService.getToSignRequests(recipientUser.getEppn());
        toSignSignRequests.addAll(getSharedToSignSignRequests(recipientUser.getEppn()));
        if (toSignSignRequests.size() > 0) {
            recipientUser.setLastSendAlertDate(date);
            mailService.sendSignRequestSummaryAlert(Arrays.asList(recipientUser.getEmail()), toSignSignRequests);
        }
    }

    @Transactional
    public void addDocumentsToSignBook(Long signBookId, MultipartFile[] multipartFiles, String authUserEppn) throws EsupSignatureIOException {
        int i = 0;
        SignBook signBook = getById(signBookId);
        for (MultipartFile multipartFile : multipartFiles) {
            SignRequest signRequest = signRequestService.createSignRequest(fileService.getNameOnly(multipartFile.getOriginalFilename()), signBook, authUserEppn, authUserEppn);
            try {
                signRequestService.addDocsToSignRequest(signRequest, true, i, new ArrayList<>(), multipartFile);
            } catch (EsupSignatureIOException e) {
                logger.error("revert signbook creation due to error : " + e.getMessage());
                deleteDefinitive(signBookId);
                throw new EsupSignatureIOException(e.getMessage(), e);
            }
            i++;
        }
    }

    public SignBook addDocsInNewSignBookSeparated(String title, String name, String prefix, MultipartFile[] multipartFiles, User authUser) throws EsupSignatureIOException {
        SignBook signBook = createSignBook(title, name, null, prefix, "", authUser, true);
        addDocumentsToSignBook(signBook.getId(), multipartFiles, authUser.getEppn());
        return signBook;
    }

    @Transactional
    public SignBook addDocsInNewSignBookGrouped(String name, MultipartFile[] multipartFiles, String authUserEppn) throws EsupSignatureIOException {
        User authUser = userService.getByEppn(authUserEppn);
        SignBook signBook = createSignBook(name, name, null, "","", authUser, false);
        SignRequest signRequest = signRequestService.createSignRequest(null, signBook, authUserEppn, authUserEppn);
        signRequestService.addDocsToSignRequest(signRequest, true, 0, new ArrayList<>(), multipartFiles);
        logger.info("signRequest : " + signRequest.getId() + " added to signBook" + signBook.getName() + " - " + signBook.getId());
        return signBook;
    }

    @Transactional
    public Map<SignBook, String> sendSignRequest(String title, MultipartFile[] multipartFiles, SignType signType, Boolean allSignToComplete, Boolean userSignFirst, Boolean pending, String comment, List<String> recipientsCCEmails, List<String> recipientsEmails, List<JsonExternalUserInfo> externalUsersInfos, User user, User authUser, boolean forceSendEmail, Boolean forceAllSign, String targetUrl) throws EsupSignatureException, EsupSignatureIOException, EsupSignatureFsException {
        if(forceAllSign == null) forceAllSign = false;
        if (!signService.checkSignTypeDocType(signType, multipartFiles[0])) {
            throw new EsupSignatureException("Impossible de demander une signature visuelle sur un document du type " + multipartFiles[0].getContentType());
        }
        String name = fileService.getNameOnly(multipartFiles[0].getOriginalFilename());
        if(title == null || title.isEmpty()) {
            title = "";
        }
        SignBook signBook = addDocsInNewSignBookSeparated(title, name, "Demande simple", multipartFiles, user);
        signBook.setForceAllDocsSign(forceAllSign);
        try {
            sendCCEmail(signBook.getId(), recipientsCCEmails);
        } catch (EsupSignatureMailException e) {
            throw new EsupSignatureException(e.getMessage());
        }
        if(targetUrl != null && !targetUrl.isEmpty()) {
            signBook.getLiveWorkflow().getTargets().add(targetService.createTarget(targetUrl));
        }
        return sendSignBook(signBook, signType, allSignToComplete, userSignFirst, pending, comment, recipientsEmails, externalUsersInfos, user, authUser, forceSendEmail);
    }

    public Map<SignBook, String> sendSignBook(SignBook signBook, SignType signType, Boolean allSignToComplete, Boolean userSignFirst, Boolean pending, String comment, List<String> recipientsEmails, List<JsonExternalUserInfo> externalUsersInfos, User user, User authUser, boolean forceSendEmail) throws EsupSignatureException {
        String message = null;
        if (allSignToComplete == null) {
            allSignToComplete = false;
        }
        if(userSignFirst != null && userSignFirst) {
            signBook.getLiveWorkflow().getLiveWorkflowSteps().add(liveWorkflowStepService.createLiveWorkflowStep(null,false, null, true, false,false, SignType.pdfImageStamp, Collections.singletonList(user.getEmail()), null));
        }
        signBook.getLiveWorkflow().getLiveWorkflowSteps().add(liveWorkflowStepService.createLiveWorkflowStep(null,false, null, true, false, allSignToComplete, signType, recipientsEmails, externalUsersInfos));
        signBook.getLiveWorkflow().setCurrentStep(signBook.getLiveWorkflow().getLiveWorkflowSteps().get(0));
        workflowService.dispatchSignRequestParams(signBook);
        if (pending != null && pending) {
            pendingSignBook(signBook.getId(), null, user.getEppn(), authUser.getEppn(), forceSendEmail);
        } else {
            message = "Après vérification/annotation, vous devez cliquer sur 'Démarrer le circuit' pour transmettre la demande aux participants";
        }
        if (comment != null && !comment.isEmpty()) {
            signBook.setDescription(comment);
        }
        Map<SignBook, String> signBookStringMap = new HashMap<>();
        signBookStringMap.put(signBook, message);
        if(recipientsEmails != null) {
            userPropertieService.createUserPropertieFromMails(userService.getByEppn(authUser.getEppn()), recipientsEmails);
        }
        return signBookStringMap;
    }

    @Transactional
    public void initWorkflowAndPendingSignBook(Long signRequestId, List<String> recipientsEmails, List<String> allSignToCompletes, List<JsonExternalUserInfo> externalUsersInfos, List<String> targetEmails, String userEppn, String authUserEppn) throws EsupSignatureFsException, EsupSignatureException {
        SignRequest signRequest = signRequestService.getById(signRequestId);
        SignBook signBook = signRequest.getParentSignBook();
        if(signBook.getStatus().equals(SignRequestStatus.draft)) {
            if (signBook.getLiveWorkflow().getWorkflow() != null) {
                List<Target> targets = new ArrayList<>(workflowService.getById(signBook.getLiveWorkflow().getWorkflow().getId()).getTargets());
                Workflow workflow = workflowService.computeWorkflow(signBook.getLiveWorkflow().getWorkflow().getId(), recipientsEmails, allSignToCompletes, userEppn, false);
                workflowService.importWorkflow(signBook, workflow, externalUsersInfos);
                signRequestService.nextWorkFlowStep(signBook);
                targetService.copyTargets(targets, signBook, targetEmails);
                if(recipientsEmails != null) {
                    for (String recipientEmail : recipientsEmails) {
                        userPropertieService.createUserPropertieFromMails(userService.getByEppn(authUserEppn), Collections.singletonList(recipientEmail.split("\\*")[1]));
                    }
                }
            }
            pendingSignBook(signBook.getId(), null, userEppn, authUserEppn, false);
        }
    }

    @Transactional
    public void pendingSignBook(Long signBookId, Data data, String userEppn, String authUserEppn, boolean forceSendEmail) throws EsupSignatureException {
        SignBook signBook = getById(signBookId);
        LiveWorkflowStep liveWorkflowStep = signBook.getLiveWorkflow().getCurrentStep();
        updateStatus(signBook, SignRequestStatus.pending, "Circuit envoyé pour signature de l'étape " + signBook.getLiveWorkflow().getCurrentStepNumber(), "SUCCESS", signBook.getComment(), userEppn, authUserEppn);
        boolean emailSended = false;
        for(SignRequest signRequest : signBook.getSignRequests()) {
            if(signBook.getLiveWorkflow() != null && signBook.getLiveWorkflow().getCurrentStep() != null && signBook.getLiveWorkflow().getCurrentStep().getAutoSign()) {
                signBook.getLiveWorkflow().getCurrentStep().setSignType(SignType.certSign);
                signBook.getLiveWorkflow().getCurrentStep().getRecipients().add(recipientService.createRecipient(userService.getSystemUser()));
            }
            if(!signRequest.getStatus().equals(SignRequestStatus.refused)) {
                if (liveWorkflowStep != null) {
                    signRequestService.pendingSignRequest(signRequest, userEppn);
                    if (!emailSended) {
                        try {
                            mailService.sendEmailAlerts(signRequest, userEppn, data, forceSendEmail);
                            emailSended = true;
                        } catch (EsupSignatureMailException e) {
                            throw new EsupSignatureException(e.getMessage());
                        }
                    }
                    for (Recipient recipient : signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getRecipients()) {
                        if (recipient.getUser().getUserType().equals(UserType.external)) {
                            try {
                                otpService.generateOtpForSignRequest(signRequest.getId(), recipient.getUser());
                            } catch (EsupSignatureMailException e) {
                                throw new EsupSignatureException(e.getMessage());
                            }
                        }
                    }
                    logger.info("Circuit " + signBook.getId() + " envoyé pour signature de l'étape " + signBook.getLiveWorkflow().getCurrentStepNumber());
                    if(signBook.getLiveWorkflow().getCurrentStep().getAutoSign()) {
                        for(SignRequest signRequest1 : signBook.getSignRequests()) {
                            List<SignRequestParams> signRequestParamses = signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getSignRequestParams();
                            signRequestParamses.get(0).setExtraDate(true);
                            signRequestParamses.get(0).setAddExtra(true);
                            signRequestParamses.get(0).setExtraOnTop(true);
                            signRequestParamses.get(0).setAddWatermark(true);
                            signRequestParamses.get(0).setSignWidth(200);
                            signRequestParamses.get(0).setSignHeight(100);
                            signRequestParamses.get(0).setExtraText(signBook.getLiveWorkflow().getCurrentStep().getWorkflowStep().getCertificat().getKeystore().getFileName().replace(",", "\n"));
                            if(signRequestParamses.size() > 0) {
                                signRequest1.setSignable(true);
                                try {
                                    sign(signRequest1, "", "auto", signRequestParamses, null, userService.getSystemUser(), userService.getSystemUser(), null, "");
                                } catch (IOException | InterruptedException | EsupSignatureMailException e) {
                                    logger.error("auto sign fail", e);
                                }
                            } else {
                                try {
                                    refuse(signRequest1.getId(), "Signature refusée par le système automatique",  "system", "system");
                                } catch (EsupSignatureMailException e) {
                                    logger.error("auto refuse fail", e);
                                }
                            }
                        }
                    }
                } else {
                    completeSignBook(signBook.getId(), userEppn);
                    logger.info("Circuit " + signBook.getId() + " terminé car ne contient pas d'étape");
                    break;
                }
            }
        }
    }

    public void completeSignBook(Long signBookId, String userEppn) throws EsupSignatureException {
        SignBook signBook = getById(signBookId);
        if (!signBook.getCreateBy().equals(userService.getSchedulerUser())) {
            try {
                mailService.sendCompletedMail(signBook, userEppn);
                mailService.sendCompletedCCMail(signBook);
            } catch (EsupSignatureMailException e) {
                throw new EsupSignatureException(e.getMessage());
            }
        }
        updateStatus(signBook, SignRequestStatus.completed, "Tous les documents sont signés", "SUCCESS", "", userEppn, userEppn);
        signRequestService.completeSignRequests(signBook.getSignRequests(), userEppn);
    }

    public List<SignRequest> getSignRequestsForCurrentUserByStatus(String userEppn, String authUserEppn, String statusFilter) {
        List<SignRequest> signRequestList = new ArrayList<>();
        List<SignBook> signBooks = getSignBooks(userEppn, statusFilter, null, null, null, Pageable.unpaged()).getContent();
        if(!userEppn.equals(authUserEppn)) {
            for(SignBook signBook: signBooks) {
                for(SignRequest signRequest : signBook.getSignRequests()) {
                    if(userShareService.checkAllShareTypesForSignRequest(userEppn, authUserEppn, signRequest) || signRequestService.getSharedSignedSignRequests(authUserEppn).contains(signRequest)) {
                        signRequestList.add(signRequest);
                    }
                }
            }
        } else {
            for(SignBook signBook: signBooks) {
                signRequestList.addAll(signBook.getSignRequests());
            }
        }
        return signRequestList.stream().sorted(Comparator.comparing(SignRequest::getId)).collect(Collectors.toList());
    }

    public List<SignRequest> getSharedToSignSignRequests(String userEppn) {
        List<SignRequest> sharedSignRequests = new ArrayList<>();
        List<SignBook> sharedSignBooks = getSharedSignBooks(userEppn);
        for(SignBook signBook: sharedSignBooks) {
            sharedSignRequests.addAll(signBook.getSignRequests());
        }
        return sharedSignRequests;
    }

    @Transactional
    public boolean initSign(Long signRequestId, String signRequestParamsJsonString, String comment, String formData, String password, String certType, Long userShareId, String userEppn, String authUserEppn) throws EsupSignatureMailException, IOException, InterruptedException, EsupSignatureException {
        SignRequest signRequest = getSignRequestsFullById(signRequestId, userEppn, authUserEppn);
        Map<String, String> formDataMap = null;
        List<String> toRemoveKeys = new ArrayList<>();
        if(formData != null) {
            try {
                TypeReference<Map<String, String>> type = new TypeReference<>(){};
                ObjectMapper objectMapper = new ObjectMapper();
                formDataMap = objectMapper.readValue(formData, type);
                formDataMap.remove("_csrf");
                Data data = dataService.getBySignBook(signRequest.getParentSignBook());
                if(data != null && data.getForm() != null) {
                    List<Field> fields = preFillService.getPreFilledFieldsByServiceName(data.getForm().getPreFillType(), data.getForm().getFields(), userService.getUserByEppn(userEppn), signRequest);
                    for(Map.Entry<String, String> entry : formDataMap.entrySet()) {
                        Optional<Field> formfield = fields.stream().filter(f -> f.getName().equals(entry.getKey())).findFirst();
                        if(formfield.isPresent()) {
                            if(formfield.get().getWorkflowSteps().contains(signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getWorkflowStep())) {
                                if(formfield.get().getExtValueType() == null || !formfield.get().getExtValueType().equals("system")) {
                                    data.getDatas().put(entry.getKey(), entry.getValue());
                                } else {
                                    if(!formfield.get().getDefaultValue().isEmpty()) {
                                        data.getDatas().put(entry.getKey(), formfield.get().getDefaultValue());
                                    }
                                }
                            }
                        } else {
                            toRemoveKeys.add(entry.getKey());
                        }
                    }
                    for (String toRemoveKey : toRemoveKeys) {
                        formDataMap.remove(toRemoveKey);
                    }
                }
            } catch (IOException e) {
                logger.error("form datas error", e);
            }
        }
        List<SignRequestParams> signRequestParamses;
        if (signRequestParamsJsonString == null) {
            signRequestParamses = signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getSignRequestParams();
        } else {
            signRequestParamses = signRequestParamsService.getSignRequestParamsFromJson(signRequestParamsJsonString);
        }
        if (signRequest.getCurrentSignType().equals(SignType.nexuSign)) {
            signRequestParamsService.copySignRequestParams(signRequest, signRequestParamses);
            return false;
        } else {
            User user = userService.getByEppn(userEppn);
            User authUser = userService.getByEppn(authUserEppn);
            sign(signRequest, password, certType, signRequestParamses, formDataMap, user, authUser, userShareId, comment);
            return true;
        }
    }

    @Transactional
    public String initMassSign(String userEppn, String authUserEppn, String ids, HttpSession httpSession, String password, String certType) throws IOException, InterruptedException, EsupSignatureMailException, EsupSignatureException {
        String error = null;
        TypeReference<List<String>> type = new TypeReference<>(){};
        ObjectMapper objectMapper = new ObjectMapper();
        List<String> idsString = objectMapper.readValue(ids, type);
        List<Long> idsLong = new ArrayList<>();
        idsString.forEach(s -> idsLong.add(Long.parseLong(s)));
        Object userShareString = httpSession.getAttribute("userShareId");
        Report report = reportService.createReport(authUserEppn);
        Long userShareId = null;
        if(userShareString != null) {
            userShareId = Long.valueOf(userShareString.toString());
        }
        for (Long id : idsLong) {
            SignRequest signRequest = signRequestService.getById(id);
            if (!signRequest.getStatus().equals(SignRequestStatus.pending)) {
                reportService.addSignRequestToReport(report.getId(), signRequest, ReportStatus.badStatus);
            } else if (signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getSignType().equals(SignType.nexuSign)) {
                reportService.addSignRequestToReport(report.getId(), signRequest, ReportStatus.signTypeNotCompliant);
            } else if (signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getRecipients().stream().noneMatch(r -> r.getUser().getEppn().equals(authUserEppn))) {
                reportService.addSignRequestToReport(report.getId(), signRequest, ReportStatus.userNotInCurrentStep);
                error = messageSource.getMessage("report.reportstatus." + ReportStatus.userNotInCurrentStep, null, Locale.FRENCH);
            } else if (signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getSignRequestParams().isEmpty()) {
                reportService.addSignRequestToReport(report.getId(), signRequest, ReportStatus.noSignField);
                error = messageSource.getMessage("report.reportstatus." + ReportStatus.noSignField, null, Locale.FRENCH);
            }
            else if (signRequest.getStatus().equals(SignRequestStatus.pending) && initSign(id,null, null, null, password, certType, userShareId, userEppn, authUserEppn)) {
                reportService.addSignRequestToReport(report.getId(), signRequest, ReportStatus.signed);
                error = null;
            }
            else {
                reportService.addSignRequestToReport(report.getId(), signRequest, ReportStatus.error);
            }
        }
        return error;
    }

    public void sign(SignRequest signRequest, String password, String certType, List<SignRequestParams> signRequestParamses, Map<String, String> formDataMap, User user, User authUser, Long userShareId, String comment) throws EsupSignatureException, IOException, InterruptedException, EsupSignatureMailException {
        User signerUser = user;
        if(userShareId != null) {
            UserShare userShare = userShareService.getById(userShareId);
            if (userShare.getUser().getEppn().equals(user.getEppn()) && userShare.getSignWithOwnSign() != null && userShare.getSignWithOwnSign()) {
                signerUser = userService.getByEppn(authUser.getEppn());
            }
        }
        List<Document> toSignDocuments = signService.getToSignDocuments(signRequest.getId());
        SignType signType = signRequest.getCurrentSignType();
        InputStream filledInputStream;
        if(!signRequestService.isNextWorkFlowStep(signRequest.getParentSignBook())) {
            Data data = dataService.getBySignRequest(signRequest);
            if(data != null && data.getForm() != null) {
                Form form = data.getForm();
                for (Field field : form.getFields()) {
                    if ("default".equals(field.getExtValueServiceName()) && "system".equals(field.getExtValueType())) {
                        if (field.getExtValueReturn().equals("id")) {
                            data.getDatas().put(field.getName(), "" + signRequest.getToken());
                            formDataMap.put(field.getName(), "" + signRequest.getToken());
                        }
                    }
                }
            }
        }
        byte[] bytes = toSignDocuments.get(0).getInputStream().readAllBytes();
        if(formDataMap != null && formDataMap.size() > 0 && toSignDocuments.get(0).getContentType().equals("application/pdf") && validationService.validate(new ByteArrayInputStream(bytes), null).getSimpleReport().getSignatureIdList().size() == 0) {
            filledInputStream = pdfService.fill(toSignDocuments.get(0).getInputStream(), formDataMap, signRequestService.isStepAllSignDone(signRequest.getParentSignBook()));
        } else {
            filledInputStream = toSignDocuments.get(0).getInputStream();
        }
        boolean visual = true;
        if( signType.equals(SignType.visa) || signType.equals(SignType.hiddenVisa)  || signType.equals(SignType.pdfImageStamp)) {
            InputStream signedInputStream = filledInputStream;
            String fileName = toSignDocuments.get(0).getFileName();
            if(signType.equals(SignType.hiddenVisa)) visual = false;
            if(signRequestParamses.size() == 0 && visual) {
                throw new EsupSignatureException("Il manque une signature !");
            }
            List<Log> lastSignLogs = new ArrayList<>();
            if (toSignDocuments.size() == 1 && toSignDocuments.get(0).getContentType().equals("application/pdf") && visual) {
                for(SignRequestParams signRequestParams : signRequestParamses) {
                    signedInputStream = pdfService.stampImage(signedInputStream, signRequest, signRequestParams, 1, signerUser);
                    lastSignLogs.add(signRequestService.updateStatus(signRequest.getId(), signRequest.getStatus(), "Apposition de la signature",  "SUCCESS", signRequestParams.getSignPageNumber(), signRequestParams.getxPos(), signRequestParams.getyPos(), signRequest.getParentSignBook().getLiveWorkflow().getCurrentStepNumber(), user.getEppn(), authUser.getEppn()));
                }
            }
            if ((signRequestService.isStepAllSignDone(signRequest.getParentSignBook()))) {
                signedInputStream = pdfService.convertGS(pdfService.writeMetadatas(signedInputStream, fileName, signRequest, lastSignLogs), signRequest.getToken());
            }
            applyEndOfSignRules(signRequest.getId(), user.getEppn(), authUser.getEppn(), signType, comment);
            documentService.addSignedFile(signRequest, signedInputStream, signRequest.getTitle() + "." + fileService.getExtension(toSignDocuments.get(0).getFileName()), toSignDocuments.get(0).getContentType());
        } else {
            if (toSignDocuments.size() == 1 && toSignDocuments.get(0).getContentType().equals("application/pdf")) {
                signRequestParamsService.copySignRequestParams(signRequest, signRequestParamses);
                toSignDocuments.get(0).setTransientInputStream(pdfService.addOutLine(signRequest, filledInputStream, user, new Date(), new SimpleDateFormat()));
            } else {
                visual = false;
            }
            if(signRequestParamses.size() == 0 && visual) {
                throw new EsupSignatureException("Il manque une signature !");
            }
            signService.certSign(signRequest, signerUser, password, certType, visual);
            applyEndOfSignRules(signRequest.getId(), user.getEppn(), authUser.getEppn(), SignType.certSign, comment);
        }
        customMetricsService.incValue("esup-signature.signrequests", "signed");
    }

    @Transactional
    public void applyEndOfSignRules(Long signRequestId, String userEppn, String authUserEppn, SignType signType, String comment) throws EsupSignatureException {
        SignRequest signRequest = signRequestService.getById(signRequestId);
        if ( signType.equals(SignType.visa) || signType.equals(SignType.hiddenVisa) ) {
            if(comment != null && !comment.isEmpty()) {
                commentService.create(signRequest.getId(), comment, 0, 0, 0, null, true, null, userEppn);
                signRequestService.updateStatus(signRequest.getId(), SignRequestStatus.checked, "Visa",  "SUCCESS", null, null, null, signRequest.getParentSignBook().getLiveWorkflow().getCurrentStepNumber(), userEppn, authUserEppn);
            } else {
                signRequestService.updateStatus(signRequest.getId(), SignRequestStatus.checked, "Visa", "SUCCESS", userEppn, authUserEppn);
            }
        } else {
            if(comment != null && !comment.isEmpty()) {
                commentService.create(signRequest.getId(), comment, 0, 0, 0, null,true, null, userEppn);
                signRequestService.updateStatus(signRequest.getId(), SignRequestStatus.signed, "Signature", "SUCCESS", null, null, null, signRequest.getParentSignBook().getLiveWorkflow().getCurrentStepNumber(), userEppn, authUserEppn);
            } else {
                signRequestService.updateStatus(signRequest.getId(), SignRequestStatus.signed, "Signature", "SUCCESS", userEppn, authUserEppn);
            }
        }
        recipientService.validateRecipient(signRequest, userEppn);
        if (signRequestService.isSignRequestCompleted(signRequest)) {
            signRequestService.completeSignRequests(Collections.singletonList(signRequest), authUserEppn);
            if (signRequestService.isCurrentStepCompleted(signRequest)) {
                for (Recipient recipient : signRequest.getRecipientHasSigned().keySet()) {
                    recipient.setSigned(!signRequest.getRecipientHasSigned().get(recipient).getActionType().equals(ActionType.none));
                }
                if (signRequestService.nextWorkFlowStep(signRequest.getParentSignBook())) {
                    pendingSignBook(signRequest.getParentSignBook().getId(), null, userEppn, authUserEppn, false);
                } else {
                    completeSignBook(signRequest.getParentSignBook().getId(), authUserEppn);
                }
            }
        } else {
            signRequestService.updateStatus(signRequest.getId(), SignRequestStatus.pending, "Demande incomplète", "SUCCESS", userEppn, authUserEppn);
        }
    }

    public void refuseSignBook(SignBook signBook, String comment, String userEppn, String authUserEppn) throws EsupSignatureMailException {
        mailService.sendRefusedMail(signBook, comment, userEppn);
        for(SignRequest signRequest : signBook.getSignRequests()) {
            commentService.create(signRequest.getId(), comment, 0, 0, 0, null, true, "#FF7EB9", userEppn);
        }
        updateStatus(signBook, SignRequestStatus.refused, "Cette demande a été refusée, ceci annule toute la procédure", "SUCCESS", comment, userEppn, authUserEppn);
        for(SignRequest signRequest : signBook.getSignRequests()) {
            signRequestService.updateStatus(signRequest.getId(), SignRequestStatus.refused, "Refusé", "SUCCESS", null, null, null, signBook.getLiveWorkflow().getCurrentStepNumber(), userEppn, authUserEppn);
            for(Recipient recipient : signBook.getLiveWorkflow().getCurrentStep().getRecipients()) {
                if(recipient.getUser().getEppn().equals(userEppn)) {
                    Action action = signRequest.getRecipientHasSigned().get(recipient);
                    action.setActionType(ActionType.refused);
                    action.setUserIp(webUtilsService.getClientIp());
                    action.setDate(new Date());
                    recipient.setSigned(true);
                }
            }
        }
    }

    @Transactional
    public void refuse(Long signRequestId, String comment, String userEppn, String authUserEppn) throws EsupSignatureMailException {
        SignRequest signRequest = signRequestService.getById(signRequestId);
        SignBook signBook = signRequest.getParentSignBook();
        if(signBook.getSignRequests().size() > 1 && (signBook.getForceAllDocsSign() == null || !signBook.getForceAllDocsSign())) {
            commentService.create(signRequest.getId(), comment, 0, 0, 0, null, true, "#FF7EB9", userEppn);
            signRequestService.updateStatus(signRequest.getId(), SignRequestStatus.refused, "Refusé", "SUCCESS", null, null, null, signRequest.getParentSignBook().getLiveWorkflow().getCurrentStepNumber(), userEppn, authUserEppn);
            for (Recipient recipient : signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getRecipients()) {
                if (recipient.getUser().getEppn().equals(userEppn)) {
                    Action action = signRequest.getRecipientHasSigned().get(recipient);
                    action.setActionType(ActionType.refused);
                    action.setUserIp(webUtilsService.getClientIp());
                    action.setDate(new Date());
                    recipient.setSigned(true);
                }
            }
            List<SignRequest> signRequests = new ArrayList<>(signBook.getSignRequests());
            signRequests.remove(signRequest);
            boolean test = signRequests.stream().noneMatch(signRequest1 -> signRequest1.getStatus().equals(SignRequestStatus.pending));
            if(test) {
                updateStatus(signBook, SignRequestStatus.completed, "La demande est terminée un des documents à été refusé", "WARN", comment, userEppn, authUserEppn);
            }
        } else {
            refuseSignBook(signRequest.getParentSignBook(), comment, userEppn, authUserEppn);
        }
    }

    public void addStep(Long id, List<String> recipientsEmails, SignType signType, Boolean allSignToComplete, String authUserEppn) throws EsupSignatureException {
        SignRequest signRequest = signRequestService.getById(id);
        addLiveStep(signRequest.getParentSignBook().getId(), recipientsEmails, signRequest.getParentSignBook().getLiveWorkflow().getCurrentStepNumber(), allSignToComplete, signType, false, null, true, false, authUserEppn);
    }

    public SignRequest startWorkflow(Long id, MultipartFile[] multipartFiles, String createByEppn, String name, List<String> recipientEmails, List<String> allSignToCompletes, List<String> targetEmails) throws EsupSignatureFsException, EsupSignatureException, EsupSignatureIOException {
        Workflow workflow = workflowService.getById(id);
        User user = userService.getByEppn(createByEppn);
        if(name == null || name.isEmpty()) {
            name = workflow.getDescription();
        }
        SignBook signBook = createSignBook(name, name, workflow, "", null, user, true);
        signBook.getLiveWorkflow().setWorkflow(workflow);
        SignRequest signRequest = signRequestService.createSignRequest(multipartFiles[0].getOriginalFilename(), signBook, createByEppn, createByEppn);
        signRequestService.addDocsToSignRequest(signRequest, false, 0, new ArrayList<>(), multipartFiles);
        initWorkflowAndPendingSignBook(signRequest.getId(), recipientEmails, allSignToCompletes, null, targetEmails, createByEppn, createByEppn);
        return signRequest;
    }

    @Transactional
    public void addWorkflowToSignBook(SignBook signBook, String authUserEppn, Long workflowSignBookId) throws EsupSignatureException {
        Workflow workflow = workflowService.getById(workflowSignBookId);
        workflowService.importWorkflow(signBook, workflow, null);
        signRequestService.nextWorkFlowStep(signBook);
        pendingSignBook(signBook.getId(), null, authUserEppn, authUserEppn, false);
    }

    @Transactional
    public void nextStepAndPending(Long signBookId, Data data, String userEppn, String authUserEppn) throws EsupSignatureException {
        SignBook signBook = getById(signBookId);
        signRequestService.nextWorkFlowStep(signBook);
        pendingSignBook(signBook.getId(), data, userEppn, authUserEppn, true);
    }

    @Transactional
    public boolean startLiveWorkflow(SignBook signBook, String userEppn, String authUserEppn, Boolean start) throws EsupSignatureException {
        if(signBook.getLiveWorkflow().getLiveWorkflowSteps().size() >  0) {
            signBook.getLiveWorkflow().setCurrentStep(signBook.getLiveWorkflow().getLiveWorkflowSteps().get(0));
            if(start != null && start) {
                workflowService.dispatchSignRequestParams(signBook);
                pendingSignBook(signBook.getId(), null, userEppn, authUserEppn, false);
            }
            return true;
        }else {
            return false;
        }
    }

    @Transactional
    public int importFilesFromSource(Long workflowId, User user, User authUser) throws EsupSignatureFsException {
        Workflow workflow = workflowService.getById(workflowId);
        int nbImportedFiles = 0;
        if (workflow.getDocumentsSourceUri() != null && !workflow.getDocumentsSourceUri().equals("")) {
            logger.info("retrieve from " + workflow.getProtectedDocumentsSourceUri());
            FsAccessService fsAccessService = fsAccessFactoryService.getFsAccessService(workflow.getDocumentsSourceUri());
            if (fsAccessService != null) {
                try {
                    fsAccessService.open();
                    fsAccessService.createURITree(workflow.getDocumentsSourceUri());
                    List<FsFile> fsFiles = new ArrayList<>(fsAccessService.listFiles(workflow.getDocumentsSourceUri() + "/"));
                    if (fsFiles.size() > 0) {
                        int j = 0;
                        for (FsFile fsFile : fsFiles) {
                            logger.info("adding file : " + fsFile.getName());
                            ByteArrayOutputStream baos = fileService.copyInputStream(fsFile.getInputStream());
                            Map<String, String> metadatas = pdfService.readMetadatas(new ByteArrayInputStream(baos.toByteArray()));
                            String documentName = fsFile.getName();
                            if (metadatas.get("Title") != null && !metadatas.get("Title").isEmpty()) {
                                documentName = metadatas.get("Title");
                            }
                            SignBook signBook = createSignBook(workflow.getTitle(), fileService.getNameOnly(documentName), workflow, "",null, user, false);
                            signBook.getLiveWorkflow().setWorkflow(workflow);
                            SignRequest signRequest = signRequestService.createSignRequest(null, signBook, user.getEppn(), authUser.getEppn());
                            if (fsFile.getCreateBy() != null && userService.getByEppn(fsFile.getCreateBy()) != null) {
                                user = userService.getByEppn(fsFile.getCreateBy());
                            }
                            signRequestService.addDocsToSignRequest(signRequest, true, j, new ArrayList<>(), fileService.toMultipartFile(new ByteArrayInputStream(baos.toByteArray()), fsFile.getName(), fsFile.getContentType()));
                            j++;
                            if (workflow.getScanPdfMetadatas()) {
                                String signType = metadatas.get("sign_type_default_val");
                                User creator = userService.createUserWithEppn(metadatas.get("Creator"));
                                if (creator != null) {
                                    signRequest.setCreateBy(creator);
                                    signBook.setCreateBy(creator);
                                } else {
                                    signRequest.setCreateBy(userService.getSystemUser());
                                    signBook.setCreateBy(userService.getSystemUser());
                                }
                                int i = 0;
                                for (String metadataKey : metadatas.keySet()) {
                                    String[] keySplit = metadataKey.split("_");
                                    if (keySplit[0].equals("sign") && keySplit[1].contains("step")) {
                                        ObjectMapper mapper = new ObjectMapper();
                                        TypeReference<List<String>> type = new TypeReference<>(){};
                                        List<String> recipientList = mapper.readValue(metadatas.get(metadataKey), type);
                                        WorkflowStep workflowStep = null;
                                        if(workflow.getWorkflowSteps().size() > i) {
                                            workflowStep = workflow.getWorkflowSteps().get(i);
                                        }
                                        LiveWorkflowStep liveWorkflowStep = liveWorkflowStepService.createLiveWorkflowStep(workflowStep, false, null, true, false, false, SignType.valueOf(signType), recipientList, null);
                                        signBook.getLiveWorkflow().getLiveWorkflowSteps().add(liveWorkflowStep);
                                        i++;
                                    }
                                    if (keySplit[0].equals("sign") && keySplit[1].contains("target")) {
                                        String metadataTarget = metadatas.get(metadataKey);
                                        for(Target target : workflow.getTargets()) {
                                            signBook.getLiveWorkflow().getTargets().add(targetService.createTarget(target.getTargetUri() + "/" + metadataTarget));
                                        }
                                        logger.info("target set to : " + signBook.getLiveWorkflow().getTargets().get(0).getTargetUri());
                                    }
                                }
                            } else {
                                targetService.copyTargets(workflow.getTargets(), signBook, null);
                                workflowService.importWorkflow(signBook, workflow, null);
                            }
                            nextStepAndPending(signBook.getId(), null, user.getEppn(), authUser.getEppn());
                            fsAccessService.remove(fsFile);
                            nbImportedFiles++;
                        }
                    } else {
                        logger.info("aucun fichier à importer depuis : " + workflow.getProtectedDocumentsSourceUri());
                    }
                } catch (Exception e) {
                    logger.error("error on import from " + workflow.getProtectedDocumentsSourceUri(), e.getMessage());
                }
                fsAccessService.close();
            } else {
                logger.warn("aucun service de fichier n'est disponible");
            }
        }
        return nbImportedFiles;
    }

    public SignRequest getNextSignRequest(Long signRequestId, String userEppn, String authUserEppn) {
        List<SignRequest> toSignRequests = getSignRequestsForCurrentUserByStatus(userEppn, authUserEppn, "tosign");
        Optional<SignRequest> signRequest = toSignRequests.stream().filter(signRequest1 -> signRequest1.getId().equals(signRequestId)).findFirst();
        if(signRequest.isPresent()) {
            if (toSignRequests.size() > 0) {
                if (!toSignRequests.contains(signRequest.get())) {
                    return toSignRequests.get(0);
                } else {
                    if (toSignRequests.size() > 1) {
                        int indexOfCurrentSignRequest = toSignRequests.indexOf(signRequest.get());
                        if (indexOfCurrentSignRequest == 0) {
                            return toSignRequests.get(indexOfCurrentSignRequest + 1);
                        } else if (indexOfCurrentSignRequest == toSignRequests.size() - 1) {
                            return toSignRequests.get(0);
                        } else {
                            return toSignRequests.get(indexOfCurrentSignRequest + 1);
                        }
                    }
                }
            }
        }
        return null;
    }

    public SignRequest getPreviousSignRequest(Long signRequestId, String userEppn, String authUserEppn) {
        List<SignRequest> toSignRequests = getSignRequestsForCurrentUserByStatus(userEppn, authUserEppn, "tosign");
        Optional<SignRequest> signRequest = toSignRequests.stream().filter(signRequest1 -> signRequest1.getId().equals(signRequestId)).findFirst();
        if(signRequest.isPresent()) {
            if (toSignRequests.size() > 0) {
                if (toSignRequests.size() > 1) {
                    int indexOfCurrentSignRequest = toSignRequests.indexOf(signRequest.get());
                    if (indexOfCurrentSignRequest > -1) {
                        if (indexOfCurrentSignRequest == 0) {
                            return toSignRequests.get(toSignRequests.size() - 1);
                        } else if (indexOfCurrentSignRequest == toSignRequests.size() - 1) {
                            return toSignRequests.get(indexOfCurrentSignRequest - 1);
                        } else {
                            return toSignRequests.get(indexOfCurrentSignRequest - 1);
                        }
                    }
                }
            }
        }
        return null;
    }

    @Transactional
    public void getToSignFileReportResponse(Long signRequestId, HttpServletResponse response) throws Exception {
        SignRequest signRequest = signRequestService.getById(signRequestId);
        response.setContentType("application/zip; charset=utf-8");
        response.setHeader("Content-Disposition", "inline; filename=" + URLEncoder.encode(signRequest.getTitle() + "-avec_rapport", StandardCharsets.UTF_8.toString()) + ".zip");
        response.getOutputStream().write(getZipWithDocAndReport(signRequest));
    }

    public byte[] getZipWithDocAndReport(SignRequest signRequest) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);
        String name = "";
        InputStream inputStream = null;
        if (!signRequest.getStatus().equals(SignRequestStatus.exported)) {
            if(signService.getToSignDocuments(signRequest.getId()).size() == 1) {
                List<Document> documents = signService.getToSignDocuments(signRequest.getId());
                name = documents.get(0).getFileName();
                inputStream = documents.get(0).getInputStream();
            }
        } else {
            FsFile fsFile = signRequestService.getLastSignedFsFile(signRequest);
            name = fsFile.getName();
            inputStream = fsFile.getInputStream();
        }

        if(inputStream != null) {
            int i = 0;
            for(Document document : signRequest.getAttachments()) {
                zipOutputStream.putNextEntry(new ZipEntry(i + "_" + document.getFileName()));
                IOUtils.copy(document.getInputStream(), zipOutputStream);
                zipOutputStream.write(document.getInputStream().readAllBytes());
                zipOutputStream.closeEntry();
                i++;
            }

            byte[] fileBytes = inputStream.readAllBytes();

            zipOutputStream.putNextEntry(new ZipEntry(name));
            IOUtils.copy(new ByteArrayInputStream(fileBytes), zipOutputStream);
            zipOutputStream.closeEntry();
            File reportFile = fileService.getTempFile("report.pdf");

            Reports reports = validationService.validate(new ByteArrayInputStream(fileBytes), null);

            fopService.generateSimpleReport(reports.getXmlSimpleReport(), new FileOutputStream(reportFile));
            zipOutputStream.putNextEntry(new ZipEntry("rapport-signature.pdf"));
            IOUtils.copy(new FileInputStream(reportFile), zipOutputStream);
            zipOutputStream.closeEntry();
            reportFile.delete();
        }
        zipOutputStream.close();
        return outputStream.toByteArray();
    }

    @Transactional
    public void getMultipleSignedDocuments(List<Long> ids, HttpServletResponse response) throws IOException, EsupSignatureFsException {
        response.setContentType("application/zip; charset=utf-8");
        response.setHeader("Content-Disposition", "inline; filename=" + URLEncoder.encode("alldocs", StandardCharsets.UTF_8.toString()) + ".zip");
        List<FsFile> fsFiles = new ArrayList<>();
        for(Long id : ids) {
            SignBook signBook = getById(id);
            for (SignRequest signRequest : signBook.getSignRequests()) {
                if(signRequest.getStatus().equals(SignRequestStatus.completed) || signRequest.getStatus().equals(SignRequestStatus.exported) || signRequest.getStatus().equals(SignRequestStatus.archived)) {
                    FsFile fsFile = signRequestService.getLastSignedFsFile(signRequest);
                    if(fsFile != null) {
                        fsFiles.add(fsFile);
                    }
                }
            }
        }
        ZipOutputStream zipOutputStream = new ZipOutputStream(response.getOutputStream());
        int i = 0;
        for(FsFile fsFile : fsFiles) {
            zipOutputStream.putNextEntry(new ZipEntry(i + "_" + fsFile.getName()));
            IOUtils.copy(fsFile.getInputStream(), zipOutputStream);
            zipOutputStream.write(fsFile.getInputStream().readAllBytes());
            zipOutputStream.closeEntry();
            i++;
        }
        zipOutputStream.close();
    }

    @Transactional
    public void getMultipleSignedDocumentsWithReport(List<Long> ids, HttpServletResponse response) throws Exception {
        response.setContentType("application/zip; charset=utf-8");
        response.setHeader("Content-Disposition", "inline; filename=" + URLEncoder.encode("alldocs", StandardCharsets.UTF_8.toString()) + ".zip");
        Map<byte[], String> documents = new HashMap<>();
        for(Long id : ids) {
            SignBook signBook = getById(id);
            for (SignRequest signRequest : signBook.getSignRequests()) {
                if(signRequest.getStatus().equals(SignRequestStatus.completed) || signRequest.getStatus().equals(SignRequestStatus.exported) || signRequest.getStatus().equals(SignRequestStatus.archived))
                    documents.put(getZipWithDocAndReport(signRequest), signBook.getName());
            }
        }
        ZipOutputStream zipOutputStream = new ZipOutputStream(response.getOutputStream());
        int i = 0;
        for(Map.Entry<byte[], String> document : documents.entrySet()) {
            zipOutputStream.putNextEntry(new ZipEntry(i + "_" + document.getValue() + ".zip"));
            IOUtils.copy(new ByteArrayInputStream(document.getKey()), zipOutputStream);
            zipOutputStream.write(document.getKey());
            zipOutputStream.closeEntry();
            i++;
        }
        zipOutputStream.close();
    }

    @Transactional
    public void saveWorkflow(Long signBookId, String title, String description, User user) throws EsupSignatureException {
        SignBook signBook = getById(signBookId);
        Workflow workflow = workflowService.createWorkflow(title, description, user);
        for(LiveWorkflowStep liveWorkflowStep : signBook.getLiveWorkflow().getLiveWorkflowSteps()) {
            List<String> recipientsEmails = new ArrayList<>();
            for (Recipient recipient : liveWorkflowStep.getRecipients()) {
                recipientsEmails.add(recipient.getUser().getEmail());
            }
            WorkflowStep toSaveWorkflowStep = workflowStepService.createWorkflowStep("" , liveWorkflowStep.getAllSignToComplete(), liveWorkflowStep.getSignType(), recipientsEmails.toArray(String[]::new));
            workflow.getWorkflowSteps().add(toSaveWorkflowStep);
        }
    }

    public boolean needToSign(SignRequest signRequest, String userEppn) {
        boolean needSignInWorkflow = recipientService.needSign(signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getRecipients(), userEppn);
        Recipient recipient = signRequest.getRecipientHasSigned().keySet().stream().filter(recipient1 -> recipient1.getUser().getEppn().equals(userEppn)).max(Comparator.comparing(Recipient::getId)).get();
        boolean needSign = signRequest.getRecipientHasSigned().get(recipient).getActionType().equals(ActionType.none);
        return needSign || needSignInWorkflow;
    }

    @Transactional
    public SignRequest getSignRequestsFullById(long id, String userEppn, String authUserEppn) {
        SignRequest signRequest = signRequestService.getById(id);
        if (signRequest.getStatus().equals(SignRequestStatus.pending)
                && checkUserSignRights(signRequest, userEppn, authUserEppn)
                && signRequest.getOriginalDocuments().size() > 0
                && needToSign(signRequest, userEppn)) {
            signRequest.setSignable(true);
        }
        User user = userService.getUserByEppn(userEppn);
        if ((signRequest.getStatus().equals(SignRequestStatus.pending)
                && (isUserInRecipients(signRequest, userEppn) || signRequest.getCreateBy().getEppn().equals(userEppn))) || (signRequest.getStatus().equals(SignRequestStatus.draft) && signRequest.getCreateBy().getEppn().equals(user.getEppn()))
        ) {
            signRequest.setEditable(true);
        }
        return signRequest;
    }

    public boolean isUserInRecipients(SignRequest signRequest, String userEppn) {
        boolean isInRecipients = false;
        Set<Recipient> recipients = signRequest.getRecipientHasSigned().keySet();
        for(Recipient recipient : recipients) {
            if (recipient.getUser().getEppn().equals(userEppn)) {
                isInRecipients = true;
                break;
            }
        }
        return isInRecipients;
    }

    public boolean checkUserSignRights(SignRequest signRequest, String userEppn, String authUserEppn) {
        if(userEppn.equals(authUserEppn) || userShareService.checkShareForSignRequest(userEppn, authUserEppn, signRequest, ShareType.sign)) {
            if(signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep() != null) {
                Optional<Recipient> recipient = signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getRecipients().stream().filter(r -> r.getUser().getEppn().equals(userEppn)).findFirst();
                if (recipient.isPresent()
                        && (signRequest.getStatus().equals(SignRequestStatus.pending) || signRequest.getStatus().equals(SignRequestStatus.draft))
                        && !signRequest.getRecipientHasSigned().isEmpty()
                        && signRequest.getRecipientHasSigned().get(recipient.get()).getActionType().equals(ActionType.none)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Transactional
    public List<String> getSignImagesForSignRequest(SignRequest signRequestRef, String userEppn, String authUserEppn, Long userShareId) throws EsupSignatureUserException, IOException {
        SignRequest signRequest = getSignRequestsFullById(signRequestRef.getId(), userEppn, authUserEppn);
        signRequestRef.setSignable(signRequest.getSignable());
        signRequestRef.setEditable(signRequest.getEditable());
        LinkedList<String> signImages = new LinkedList<>();
        if (signRequest.getSignedDocuments().size() > 0 || signRequest.getOriginalDocuments().size() > 0) {
            List<Document> toSignDocuments = signService.getToSignDocuments(signRequest.getId());
            if (toSignDocuments.size() == 1 && toSignDocuments.get(0).getContentType().equals("application/pdf")) {
                if(signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep() != null && !signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getSignType().equals(SignType.visa) && !signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getSignType().equals(SignType.hiddenVisa)) {
                    User user = userService.getByEppn(userEppn);
                    if(userShareId != null) {
                        UserShare userShare = userShareService.getById(userShareId);
                        if (userShare.getUser().getEppn().equals(userEppn) && userShare.getSignWithOwnSign() != null && userShare.getSignWithOwnSign()) {
                            user = userService.getByEppn(authUserEppn);
                        }
                    }
                    if (user.getSignImages().size() > 0 && user.getSignImages().get(0) != null && user.getSignImages().get(0).getSize() > 0) {
                        if (checkUserSignRights(signRequest, userEppn, authUserEppn)
                                && user.getKeystore() == null
                                && certificatService.getCertificatByUser(userEppn).size() == 0
                                && signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getSignType().equals(SignType.certSign)
                                && globalProperties.getOpenXPKIServerUrl() == null) {
                            signRequestRef.setSignable(false);
                            throw new EsupSignatureUserException("Pour signer ce document merci d’ajouter un certificat à votre profil <a href='user/users' target='_blank'>Mes paramètres</a>");
                        }
                        for (Document signImage : user.getSignImages()) {
                            signImages.add(fileService.getBase64Image(signImage));
                        }
                    } else {
                        if (signRequest.getSignable() && signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getSignType() != null && signRequest.getParentSignBook().getLiveWorkflow().getCurrentStep().getSignType().equals(SignType.pdfImageStamp)) {
                            signRequestRef.setSignable(false);
                            throw new EsupSignatureUserException("Pour signer ce document merci d'ajouter une image de votre signature dans <a href='user/users' target='_blank'>Mes paramètres</a>");

                        }
                    }
                }
            }
        }
        return signImages;
    }

}
