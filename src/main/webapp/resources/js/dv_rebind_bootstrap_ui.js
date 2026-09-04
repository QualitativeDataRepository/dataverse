/*
 * Rebind bootstrap UI components after Primefaces ajax calls
 */
function bind_bsui_components(){
    // Facet panel Filter Results btn toggle
    $(document).off('click', '[data-bs-toggle=offcanvas]').on('click', '[data-bs-toggle=offcanvas]', function() {
        $('.row-offcanvas').toggleClass('active', 200);
    });
    
    // Collapse Header Icons
    $('[id^="panelCollapse"]').off('shown.bs.collapse').on('shown.bs.collapse', function () {
      $(this).siblings('div.panel-title').find('button').attr('aria-expanded', 'true').removeClass('collapsed');
      $(this).siblings('div.panel-title').find('span.bi').removeClass("bi-chevron-down").addClass("bi-chevron-up");
    });

    $('[id^="panelCollapse"]').off('hidden.bs.collapse').on('hidden.bs.collapse', function () {
      $(this).siblings('div.panel-title').find('button').attr('aria-expanded', 'false').addClass('collapsed');
      $(this).siblings('div.panel-title').find('span.bi').removeClass("bi-chevron-up").addClass("bi-chevron-down");
    });
    
    // Button dropdown menus 
    // Bootstrap 5 dropdowns are initialized automatically, no need for manual initialization
    
    // Hide open tooltips + popovers
    $('.bootstrap-button-tooltip, [data-bs-toggle="tooltip"]').each(function() {
        var tooltip = bootstrap.Tooltip.getInstance(this);
        if (tooltip) {
            tooltip.hide();
            tooltip.dispose();
        }
    });

    $("[data-bs-toggle='popover']").each(function() {
        var popover = bootstrap.Popover.getInstance(this);
        if (popover) {
            popover.hide();
            popover.dispose();
        }
    });

    // Tooltips + popovers
    bind_tooltip_popover();

    // Disabled pagination links
    disabledLinks();
    
    // Truncate checksums
    checksumTruncate();
    
    // Sharrre
    sharrre();
    
    // clipboard.js click to copy
    clickCopyClipboard();
    
    // Scrolling autoComplete dropdown in popups
    handle_dropdown_popup_scroll();
    
    // Dialog Listener For Calling handleResizeDialog
    PrimeFaces.widget.Dialog.prototype.postShow = function() {
        var dialog_id = this.jq.attr('id').split(/[:]+/).pop();
        handleResizeDialog(dialog_id);
    }
    
    //Fly-out sub-menu accessibility
    enableSubMenus();

    // Modal-like behavior for file action dropdown menus
    enableModalDropdowns();
}

/*
 * Make dropdown menus in the col-file-action column act ~modally:
 * a click outside the open menu only closes the menu, rather than
 * triggering any other action on the page underneath it.
 */
function enableModalDropdowns(){
    var backdropClass = 'file-action-dropdown-backdrop';

    // Always clear out any stray backdrop left over from a prior table render
    // (e.g. PrimeFaces replacing the dataTable markup while a menu was open).
    $('.' + backdropClass).remove();

    // These handlers are delegated on document (not bound directly to the menu
    // triggers), so they keep working for menus re-rendered by PrimeFaces ajax
    // updates (e.g. an update that only refreshes the filesTable) without
    // needing to be re-applied to the new DOM elements. That means we don't
    // need a "was this already set up" flag that could get out of sync with
    // reality - instead we just unbind + rebind unconditionally every time
    // bind_bsui_components() runs. Since document itself is never replaced,
    // this is idempotent and never results in stacked/duplicate handlers.
    $(document).off('show.bs.dropdown.fileActionModal hide.bs.dropdown.fileActionModal');

    $(document).on('show.bs.dropdown.fileActionModal', '.col-file-action .btn-group, .col-file-action .dropdown', function () {
        // Make sure there is never more than one backdrop at a time
        $('.' + backdropClass).remove();

        // NOTE: the backdrop is intentionally appended inside the same
        // .col-file-action cell (the trigger's own container) rather than to
        // <body>. The surrounding markup (e.g. datasetForm:tabView) creates
        // its own stacking context via z-index (currently 499), which means
        // an element appended to <body> is compared against that whole
        // ancestor block - not against the .dropdown-menu inside it - so no
        // z-index on a body-level backdrop can ever appear both above the
        // rest of the page and below the menu at the same time. Appending
        // the backdrop as a descendant of that same ancestor puts it in the
        // same local stacking context as the menu, so its z-index can be
        // compared directly/fairly against the menu's z-index, while
        // position:fixed still makes it cover the full viewport visually.
        var $container = $(this).closest('.col-file-action');

        // Add a transparent backdrop that sits above the page but below the menu
        var $backdrop = $('<div class="' + backdropClass + '"></div>').css({
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            zIndex: 999 // just below .dropdown-menu (1000) so the menu itself stays clickable
        });
        $container.append($backdrop);

        // Clicking (or touching) the backdrop closes the menu and swallows the event
        $backdrop.on('click touchstart', function (e) {
            e.preventDefault();
            e.stopPropagation();
            $('.col-file-action .show').removeClass('show');
            $backdrop.remove();
            // The backdrop click still lands on (and focuses) the containing
            // table cell, which isn't normally a focusable/selectable element.
            // Move focus back to the menu's toggle button instead of leaving
            // it (visibly, via outline/highlight) on the <td>.
            $container.find('[data-toggle="dropdown"]').first().trigger('focus');
        });
    });

    $(document).on('hide.bs.dropdown.fileActionModal', '.col-file-action .btn-group, .col-file-action .dropdown', function () {
        $('.' + backdropClass).remove();
    });
}

function bind_tooltip_popover(){
    // Initialize all tooltips
    var tooltipTriggerList = [].slice.call(document.querySelectorAll(".bootstrap-button-tooltip, [data-bs-toggle='tooltip']"));
    tooltipTriggerList.forEach(function(tooltipTriggerEl) {
        new bootstrap.Tooltip(tooltipTriggerEl);
    });

    // Initialize all popovers
    var popoverTriggerList = [].slice.call(document.querySelectorAll("[data-bs-toggle='popover']"));
    popoverTriggerList.forEach(function(popoverTriggerEl) {
        new bootstrap.Popover(popoverTriggerEl);
    });
    
    // CLOSE OPEN TOOLTIPS + POPOVERS ON BODY CLICKS
    $('body').off("touchstart").on("touchstart", function(e){
        $(".bootstrap-button-tooltip, [data-bs-toggle='tooltip']").each(function () {
            // hide any open tooltips when anywhere else in body is clicked
            if (!$(this).is(e.target) && $(this).has(e.target).length === 0 && $('div.tooltip').has(e.target).length === 0) {
                var tooltip = bootstrap.Tooltip.getInstance(this);
                if (tooltip) {
                    tooltip.hide();
                }
            }
        });
        $("a.popoverHTML, [data-bs-toggle='popover']").each(function () {
            //the 'is' for buttons that trigger popups
            //the 'has' for icons within a button that triggers a popup
            if (!$(this).is(e.target) && $(this).has(e.target).length === 0 && $('div.popover').has(e.target).length === 0) {
                var popover = bootstrap.Popover.getInstance(this);
                if (popover) {
                    popover.hide();
                }
            }
        });
    });
    
    // CLOSE OPEN TOOLTIPS ON BUTTON CLICKS
    $(document).off('click', '.bootstrap-button-tooltip').on('click', '.bootstrap-button-tooltip', function () {
        var tooltip = bootstrap.Tooltip.getInstance(this);
        if (tooltip) {
            tooltip.hide();
        }
    });
}

function toggle_dropdown(){
    $('.btn-group.show').removeClass('show');
}

function disabledLinks(){
    $(document).off('click', 'ul.pagination li a').on('click', 'ul.pagination li a', function (e) {
        if ($(this).parent().hasClass('disabled')){
            e.preventDefault();
        }
    });
}

/*
* Custom Popover with HTML code snippet
*/
function popoverHTML(popoverTitleHTML, popoverTagsHTML) {
   var popoverTemplateHTML = ['<div class="popover">',
       '<div class="arrow"></div>',
       '<h3 class="popover-header"></h3>',
       '<div class="popover-body">',
       '</div>',
       '</div>'].join('');
   var popoverContentHTML = ['<code>', popoverTagsHTML, '</code>'].join('');

   // Update to Bootstrap 5 popover initialization
   var popoverTriggerList = [].slice.call(document.querySelectorAll('a.popoverHTML'));
   popoverTriggerList.forEach(function(popoverTriggerEl) {
       new bootstrap.Popover(popoverTriggerEl, {
           title: popoverTitleHTML,
           trigger: 'hover',
           content: popoverContentHTML,
           template: popoverTemplateHTML,
           placement: "bottom",
           container: "#content",
           html: true
       });
   });
}

/*
 * Equal Div Height
 */
function post_differences(){
       var dialogHeight = $('div[id$="detailsBlocks"].ui-dialog').outerHeight();
       var dialogHeader = $('div[id$="detailsBlocks"] .ui-dialog-titlebar').outerHeight();
       var dialogScroll = dialogHeight - dialogHeader;
       $('div[id$="detailsBlocks"] .ui-dialog-content').css('height', dialogScroll);
}

/*
 * Sharrre
 */
function sharrre(){
    $('#sharrre-widget').sharrre({
        share: {
            facebook: true,
            twitter: true,
            linkedin: true
        },
        template: '<div id="sharrre-block" class="clearfix">\n\
                    <input type="hidden" id="sharrre-total" name="sharrre-total" value="{total}"/> \n\
                    <a href="#" class="sharrre-facebook" title="FaceBook"><span class="socicon socicon-facebook"/></a> \n\
                    <a href="#" class="sharrre-twitter" title="Twitter"><span class="socicon socicon-twitter"/></a> \n\
                    <a href="#" class="sharrre-linkedin" title="LinkedIn"><span class="socicon socicon-linkedin"/></a>\n\
                    </div>',
        enableHover: false,
        enableTracking: true,
        urlCurl: '',
        render: function(api, options){
            $(api.element).on('click', '.sharrre-twitter', function() {
                api.openPopup('twitter');
            });
            $(api.element).on('click', '.sharrre-facebook', function() {
                api.openPopup('facebook');
            });
            $(api.element).on('click', '.sharrre-linkedin', function() {
                api.openPopup('linkedin');
            });
            
            // Count not working... Coming soon...
            // var sharrrecount = $('#sharrre-total').val();
            // $('#sharrre-count').prepend(sharrrecount);
        }
    });
}

/*
 * Truncate dataset description content
 */
function contentTruncate(truncSelector, truncMoreBtn, truncMoreTip, truncLessBtn, truncLessTip){
    // SELECTOR ID FROM PARAMETERS
    $('#' + truncSelector).each(function () {
        
        // add responsive img class to limit width to that of container
        $(this).find('img').attr('class', 'img-fluid');
        
        // find container height
        var containerHeight = $(this).outerHeight();
        
        if (containerHeight > 250) {
            // ADD A MAX-HEIGHT TO CONTAINER
            $(this).css({'max-height':'250px','overflow-y':'hidden', 'overflow-x':'hidden', 'position':'relative'});

            // BTN LABEL TEXT, ARIA ATTR'S, FROM BUNDLE VIA PARAMETERS
            var readMoreBtn = '<button class="btn btn-link desc-more-link" type="button" data-bs-toggle="tooltip" title="' + truncMoreTip + '" aria-expanded="false" aria-controls="#' + truncSelector + '">' + truncMoreBtn + '</button>';
            var moreBlock = '<div class="more-block">' + readMoreBtn + '</div>';
            var readLessBtn = '<button class="btn btn-link desc-less-link" type="button" data-bs-toggle="tooltip" title="' + truncLessTip + '" aria-expanded="true" aria-controls="#' + truncSelector + '">' + truncLessBtn + '</button>';
            var lessBlock = '<div class="less-block">' + readLessBtn + '</div>';

            // add "Read full desc [+]" btn, background fade
            $(this).append(moreBlock);

            // Initialize tooltip on the new button
            new bootstrap.Tooltip(document.querySelector('.more-block button'));

            // show full description in summary block on "Read full desc [+]" btn click
            $(document).on('click', 'button.desc-more-link', function() {
                var tooltip = bootstrap.Tooltip.getInstance(this);
                if (tooltip) {
                    tooltip.hide();
                    tooltip.dispose();
                }
                $(this).parent('div').parent('div').css({'max-height':'none','overflow-y':'visible','position':'relative'});
                $(this).parent('div.more-block').replaceWith(lessBlock);

                // Initialize tooltip on the new button
                new bootstrap.Tooltip(document.querySelector('.less-block button'));
            });
            
            // truncate description in summary block on "Collapse desc [-]" btn click
            $(document).on('click', 'button.desc-less-link', function() {
                var tooltip = bootstrap.Tooltip.getInstance(this);
                if (tooltip) {
                    tooltip.hide();
                    tooltip.dispose();
                }
                $(this).parent('div').parent('div').css({'max-height':'250px','overflow-y':'hidden','position':'relative'});
                $(this).parent('div.less-block').replaceWith(moreBlock);
                $('html, body').animate({scrollTop: $('#' + truncSelector).offset().top - 60}, 500);

                // Initialize tooltip on the new button
                new bootstrap.Tooltip(document.querySelector('.more-block button'));
            });
        }
    });
}

/*
 * Truncate file checksums
 */
function checksumTruncate(){
    $('span.checksum-truncate').each(function () {
        var checksumText = $(this).text();
        var checksumLength = checksumText.length;
        if (checksumLength > 25) {
            // COUNT " " IN TYPE LABEL, UNF HAS NONE
            var prefixCount = (checksumText.match(/ /g) || []).length;
            
            // INDEX OF LAST ":" IN TYPE LABEL, UNF HAS MORE THAN ONE
            var labelIndex = checksumText.lastIndexOf(':');
            
            // COUNT "=" IN UNF SUFFIX
            var suffixCount = (checksumText.match(/=/g) || []).length;
            
            // TRUNCATE MIDDLE W/ "..." + FIRST/LAST 3 CHARACTERS
            // CHECK IF UNF LABEL, LESS THAN ONE " "
            if (prefixCount === 0) {
                $(this).text(checksumText.substr(0,(labelIndex + 3)) + '...' + checksumText.substr((checksumLength - suffixCount - 3),checksumLength));
            }
            else {
                $(this).text(checksumText.substr(0,(labelIndex + 5)) + '...' + checksumText.substr((checksumLength - suffixCount - 3),checksumLength));
            }
        }
    });
    $('span.checksum-tooltip').on('inserted.bs.tooltip', function () {
        $("body div.tooltip-inner").css("word-break", "break-all");
    });
}

function clickCopyClipboard(){
    // clipboard.js click to copy
    // pass selector to clipboard
    var clipboard = new ClipboardJS('button.btn-copy, span.checksum-truncate, span.btn-copy');

    clipboard.on('success', (e)=> {
        // DEV TOOL DEBUG
        // console.log(e);

        // check which selector was clicked
        // swap icon for success ok
        if ($(e.trigger).hasClass('bi')) {
            $(e.trigger).removeClass('bi-clipboard-plus').addClass('bi-check text-success');
            // then swap icon back to clipboard
            // https://stackoverflow.com/a/54270499
            setTimeout(()=> { // use arrow function
                $(e.trigger).removeClass('bi-check text-success').addClass('bi-clipboard-plus')
            }, 2000);
        }
        else {
            $(e.trigger).next('.btn-copy.bi').removeClass('bi-clipboard-plus').addClass('bi-check text-success');
            setTimeout(()=> {
                $(e.trigger).next('.btn-copy.bi').removeClass('bi-check text-success').addClass('bi-clipboard-plus')
            }, 2000);
        }
    });
    clipboard.on('error', (e)=> {
        console.log(e);
    });
}

/*
 * Select dataset/file citation onclick event
 */
function selectText(ele) {
    try {
        var div = document.createRange();
        div.setStartBefore(ele);
        div.setEndAfter(ele);
        window.getSelection().addRange(div)
    } catch (e) {
        // for internet explorer
        div = document.selection.createRange();
        div.moveToElementText(ele);
        div.select()
    }
}

/*
 * Dialog Height-Scrollable
 */
function handleResizeDialog(dialog) {
        var el = $('div[id$="' + dialog + '"]');
        var doc = $('body');
        var win = $(window);
        var elPos = '';
        
        function calculateResize() {
            var overlay = $('#' + dialog + '_modal');
            var bodyHeight = '';
            var bodyWidth = '';
        
            // position:fixed is maybe cool, but it makes the dialog not scrollable on browser level, even if document is big enough
            if (el.height() > win.height()) {
                bodyHeight = el.height() + 'px';
                elPos = 'absolute';
            }
            if (el.width() > win.width()) {
                bodyWidth = el.width() + 'px';
                elPos = 'absolute';
            }
            el.css('position', elPos);
            doc.css('width', bodyWidth);
            doc.css('height', bodyHeight);
            
            var pos = el.offset();
            if (pos.top + el.height() > doc.height()) {
                    pos.top = doc.height() - el.height();
                    overlay.css('height', bodyHeight);
                }
            if (pos.left + el.width() > doc.width()) {
                    pos.left = doc.width() - el.width();
                    overlay.css('width', bodyWidth);
                }
            var offsetX = 0;
            var offsetY = 0;
            if (elPos != 'absolute') {
                offsetX = $(window).scrollLeft();
                offsetY = $(window).scrollTop();
            }
            // scroll fix for position fixed
            if (pos.left < offsetX)
                pos.left = offsetX;
            if (pos.top < offsetY)
                pos.top = offsetY;
            el.offset(pos);
        }
        
        calculateResize();
        
        el.find('textarea').each(function(index){
            $(this).on('keyup change cut paste focus', function(){
                calculateResize();
            });
        });
}

/*
 * fixes autoComplete dropdown in popups not moving with page scroll
 */
function handle_dropdown_popup_scroll(){
    $( window ).scroll(function() {
        var isActive = $(".DropdownPopupPanel").is(':visible');
        if(isActive) {
            $(".DropdownPopupPanel").position({
                my: "left top",
                at: "left bottom",
                of: $(".DropdownPopup")
            });
        }
    });
}


function enableSubMenus() {
    // Remove previous event handlers
    $('.dropdown-submenu>a').off('keydown click');
    $('.dropdown-submenu>.dropdown-menu>li:last-of-type>a').off('keydown');
    $('.dropdown-submenu>.dropdown-menu>li:first-of-type>a').off('keydown');

    // Add keyboard navigation
    $('.dropdown-submenu>a').on('keydown', toggleSubMenu);
    $('.dropdown-submenu>.dropdown-menu>li:last-of-type>a').on('keydown', closeOnTab);
    $('.dropdown-submenu>.dropdown-menu>li:first-of-type>a').on('keydown', closeOnShiftTab);

    // Prevent default action for dropdown submenu links to allow them to act as toggles
    $('.dropdown-submenu>a').on('click', function(e) {
        e.preventDefault();
        e.stopPropagation();

        // Close all other open submenus at the same level
        $(this).parent().siblings('.dropdown-submenu').removeClass('show').find('.dropdown-menu').removeClass('show');

        // Toggle this submenu
        $(this).parent().toggleClass('show');
        $(this).next('.dropdown-menu').toggleClass('show');

        return false;
    });

    addMenuDelays();
}

function toggleSubMenu(event) {
    if (event.key === ' ' || event.key === 'Enter') {
        event.preventDefault();

        // Close all other open submenus at the same level
        $(this).parent().siblings('.dropdown-submenu').removeClass('show').find('.dropdown-menu').removeClass('show');

        // Toggle this submenu
        $(this).parent().toggleClass('show');
        $(this).next('.dropdown-menu').toggleClass('show');
    }
}

function closeOnTab(event) {
    if (event.key === 'Tab' && !event.shiftKey) {
        $(this).closest('.dropdown-submenu').removeClass('show')
            .find('.dropdown-menu').removeClass('show');
    }
}

function closeOnShiftTab(event) {
    if (event.key === 'Tab' && event.shiftKey) {
        $(this).closest('.dropdown-submenu').removeClass('show')
            .find('.dropdown-menu').removeClass('show');
    }
}

function addMenuDelays() {
    $('.dropdown-submenu').each(function() {
        var $submenu = $(this);
        var closeMenuTimer;

        // Add hover behavior
        $submenu.on('mouseenter', function() {
            clearTimeout(closeMenuTimer);

            // Close other submenus at the same level
            $(this).siblings('.dropdown-submenu').removeClass('show')
                .find('.dropdown-menu').removeClass('show');

            // Open this submenu
            $(this).addClass('show');
            $(this).find('> .dropdown-menu').addClass('show');
        });

        $submenu.on('mouseleave', function() {
            var $this = $(this);
            closeMenuTimer = setTimeout(function() {
                $this.removeClass('show');
                $this.find('.dropdown-menu').removeClass('show');
            }, 1000);
        });
    });
}

function scrollToFirstError() {
    // delay to let other oncomplete scripts finish (like scrollTop(0))
    setTimeout(function () {
        // Find all potential error indicators, including PrimeFaces and Bootstrap classes
        var $errors = $('.ui-message-error, .ui-state-error, .has-error, .ui-message-fatal');
        
        // Filter out the main alert at the top if there are field-specific errors
        var $fieldErrors = $errors.not('.alert-danger .ui-message-error').not('.alert-danger');
        var $target = $fieldErrors.length > 0 ? $fieldErrors.first() : $errors.first();

        if ($target.length > 0) {
            // Check if it's inside a collapsed panel
            var $panel = $target.closest('.collapse:not(.in)');
            if ($panel.length > 0) {
                // Find the heading that controls this panel and click it to expand
                // or just use bootstrap's collapse method
                $panel.collapse('show');
                $panel.one('shown.bs.collapse', function () {
                    $('html, body').animate({
                        scrollTop: $target.offset().top - 150
                    }, 500);
                });
            } else {
                $('html, body').animate({
                    scrollTop: $target.offset().top - 150
                }, 500);
            }
        }
    }, 250);
}

$(document).on('pfAjaxComplete', function (e, xhr, settings) {
    var validationFailed = false;
    if (xhr && xhr.pfArgs && xhr.pfArgs.validationFailed) {
        validationFailed = true;
    } else if (xhr && xhr.responseText && xhr.responseText.indexOf('validationFailed":true') !== -1) {
        validationFailed = true;
    }

    if (validationFailed) {
        scrollToFirstError();
    }
});

$(document).ready(function () {
    if ($('.alert-danger').length > 0 && $('.ui-message-error, .ui-state-error, .has-error').length > 0) {
        scrollToFirstError();
    }
});
