// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.gdpr;

import com.intellij.openapi.options.ConfigurableBase;
import com.intellij.ui.AppUIUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ConsentConfigurable extends ConfigurableBase<ConsentSettingsUi, List<Consent>> {
  private final List<Consent> myConsents;

  public ConsentConfigurable() {
    super("consents", "Data Sharing", "preferences.usage.statistics");
    myConsents = new ArrayList<>(AppUIUtil.loadConsentsForEditing());
  }

  @NotNull
  @Override
  protected List<Consent> getSettings() {
    return myConsents;
  }

  @Override
  protected ConsentSettingsUi createUi() {
    return new ConsentSettingsUi(true) {
      @Override
      public void apply(@NotNull List<Consent> consents) {
        super.apply(consents);
        AppUIUtil.saveConsents(consents);
      }
    };
  }
}
