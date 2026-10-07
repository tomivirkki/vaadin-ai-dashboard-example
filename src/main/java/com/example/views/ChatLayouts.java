package com.example.views;

import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.UploadButton;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.component.upload.UploadFileList;
import com.vaadin.flow.component.upload.UploadFileListVariant;
import com.vaadin.flow.component.upload.UploadManager;

/**
 * Static factory for the chat layout shared by AI views.
 */
final class ChatLayouts {

    private ChatLayouts() {
    }

    /**
     * Builds a chat layout: a {@link MessageList} above a row with an upload
     * button and {@link MessageInput}, wrapped in an {@link UploadDropZone}
     * that doubles as the file drop target for {@code uploadManager}.
     *
     * <p>
     * Mutates the supplied parameters: enables markdown on {@code messageList}
     * and configures {@code uploadManager} with file count/size limits and
     * accepted MIME types.
     *
     * @return the drop zone wrapping the full chat layout
     */
    static UploadDropZone build(MessageList messageList,
            MessageInput messageInput, UploadManager uploadManager) {
        messageList.setSizeFull();
        messageList.setMarkdown(true);

        uploadManager.setMaxFiles(5);
        uploadManager.setMaxFileSize(5 * 1024 * 1024);
        uploadManager.setAcceptedMimeTypes("image/*", "application/pdf",
                "text/plain");

        var uploadDropZone = new UploadDropZone(uploadManager);
        uploadDropZone.setSizeFull();

        var uploadButton = new UploadButton(uploadManager);
        uploadButton.setIcon(VaadinIcon.UPLOAD.create());

        var uploadFileList = new UploadFileList(uploadManager);
        uploadFileList.setWidthFull();
        uploadFileList.addThemeVariants(UploadFileListVariant.THUMBNAILS);

        var inputLayout = new HorizontalLayout(uploadButton, messageInput);
        inputLayout.setWidthFull();
        inputLayout.setFlexGrow(1.0, messageInput);

        var bottomLayout = new VerticalLayout(uploadFileList, inputLayout);

        var chatLayout = new VerticalLayout(messageList, bottomLayout);
        chatLayout.setSizeFull();

        uploadDropZone.setContent(chatLayout);
        return uploadDropZone;
    }
}
